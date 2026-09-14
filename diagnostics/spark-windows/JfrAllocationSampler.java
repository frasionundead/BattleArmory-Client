/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package me.lucko.spark.common.sampler.async;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.sampler.AbstractSampler;
import me.lucko.spark.common.sampler.SamplerMode;
import me.lucko.spark.common.sampler.SamplerSettings;
import me.lucko.spark.common.sampler.async.jfr.JfrReader;
import me.lucko.spark.common.sampler.window.ProfilingWindowUtils;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.util.SparkThreadFactory;
import me.lucko.spark.common.ws.ViewerSocket;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Windows fallback allocation profiler using the JDK Flight Recorder.
 *
 * <p>spark 1.10.53 cannot use async-profiler on Windows, but Java 17 ships
 * JFR on Windows and exposes the standard allocation events that spark's
 * existing JFR reader already understands.</p>
 */
public final class JfrAllocationSampler extends AbstractSampler {
    private final AsyncDataAggregator dataAggregator;
    private final Object recordingMutex = new Object();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> socketStatisticsTask;
    private Recording recording;
    private Path outputFile;
    private int window;
    private boolean stopped;

    public JfrAllocationSampler(SparkPlatform platform, SamplerSettings settings) {
        super(platform, settings);
        this.dataAggregator = new AsyncDataAggregator(settings.threadGrouper());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "spark-jfr-allocation-worker-thread");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(SparkThreadFactory.EXCEPTION_HANDLER);
            return thread;
        });
    }

    public static boolean isSupported() {
        try {
            return FlightRecorder.isAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void start() {
        super.start();

        TickHook tickHook = this.platform.getTickHook();
        if (tickHook != null) {
            this.windowStatisticsCollector.startCountingTicks(tickHook);
        }

        this.window = ProfilingWindowUtils.windowNow();
        this.windowStatisticsCollector.recordWindowStartTime(this.window);

        try {
            this.outputFile = this.platform.getTemporaryFiles().create("spark-windows-", "-allocation.jfr");
            Files.deleteIfExists(this.outputFile);

            this.recording = new Recording();
            this.recording.setName("spark-windows-allocation");
            this.recording.setToDisk(true);
            this.recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
            this.recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
            this.recording.start();
        } catch (Exception e) {
            closeRecordingQuietly();
            throw new RuntimeException("Unable to start the Windows JFR allocation profiler", e);
        }

        recordInitialGcStats();
        scheduleTimeout();
    }

    private void scheduleTimeout() {
        if (this.autoEndTime == -1) {
            return;
        }

        long delay = this.autoEndTime - System.currentTimeMillis();
        if (delay <= 0) {
            return;
        }

        this.scheduler.schedule(() -> {
            try {
                stop(false);
                this.future.complete(this);
            } catch (Throwable t) {
                this.future.completeExceptionally(t);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop(boolean cancelled) {
        synchronized (this.recordingMutex) {
            if (this.stopped) {
                return;
            }
            this.stopped = true;
        }

        super.stop(cancelled);

        try {
            if (this.recording != null) {
                try {
                    this.recording.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped by the JVM; continue with whatever data exists.
                }

                if (!cancelled) {
                    this.recording.dump(this.outputFile);
                    this.windowStatisticsCollector.measureNow(this.window);
                    aggregate();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to finish the Windows JFR allocation profile", e);
        } finally {
            closeRecordingQuietly();
            deleteOutputFileQuietly();

            if (this.socketStatisticsTask != null) {
                this.socketStatisticsTask.cancel(false);
            }
            if (this.scheduler != null) {
                this.scheduler.shutdown();
                this.scheduler = null;
            }
        }
    }

    private void aggregate() throws IOException {
        try (JfrReader reader = new JfrReader(this.outputFile)) {
            List<JfrReader.AllocationSample> samples = reader.readAllEvents(JfrReader.AllocationSample.class);
            for (JfrReader.AllocationSample sample : samples) {
                String threadName = reader.threads.get((long) sample.tid);
                if (threadName == null) {
                    continue;
                }
                if (!this.threadDumper.isThreadIncluded(sample.tid, threadName)) {
                    continue;
                }

                long value = sample.value();
                if (value <= 0) {
                    continue;
                }

                ProfileSegment segment = ProfileSegment.parseSegment(reader, sample, threadName, value);
                this.dataAggregator.insertData(segment, this.window);
            }
        }
    }

    private void closeRecordingQuietly() {
        Recording r = this.recording;
        this.recording = null;
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void deleteOutputFileQuietly() {
        if (this.outputFile != null) {
            try {
                Files.deleteIfExists(this.outputFile);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void attachSocket(ViewerSocket socket) {
        super.attachSocket(socket);
        if (this.socketStatisticsTask == null && this.scheduler != null) {
            this.socketStatisticsTask = this.scheduler.scheduleAtFixedRate(
                    this::sendStatisticsToSocket,
                    10,
                    10,
                    TimeUnit.SECONDS
            );
        }
    }

    @Override
    public SamplerMode getMode() {
        return SamplerMode.ALLOCATION;
    }

    @Override
    public SamplerData toProto(SparkPlatform platform, ExportProps exportProps) {
        SamplerData.Builder proto = SamplerData.newBuilder();
        if (exportProps.channelInfo() != null) {
            proto.setChannelInfo(exportProps.channelInfo());
        }
        writeMetadataToProto(proto, platform, exportProps.creator(), exportProps.comment(), this.dataAggregator);
        writeDataToProto(proto, this.dataAggregator, exportProps.mergeMode().get(), exportProps.classSourceLookup().get());
        return proto.build();
    }
}
