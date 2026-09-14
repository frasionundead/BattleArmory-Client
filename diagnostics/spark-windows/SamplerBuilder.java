/*
 * This file is based on spark 1.10.53 SamplerBuilder.java.
 * Upstream: https://github.com/lucko/spark
 * Upstream commit: 8c253aec4db3450dc640c4b66f74f9c8908514fa
 * License: GNU GPL v3.0
 */
package me.lucko.spark.common.sampler;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.sampler.async.AsyncProfilerAccess;
import me.lucko.spark.common.sampler.async.AsyncSampler;
import me.lucko.spark.common.sampler.async.JfrAllocationSampler;
import me.lucko.spark.common.sampler.async.SampleCollector;
import me.lucko.spark.common.sampler.java.JavaSampler;
import me.lucko.spark.common.tick.TickHook;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Builds {@link Sampler} instances.
 */
@SuppressWarnings("UnusedReturnValue")
public class SamplerBuilder {

    private SamplerMode mode = SamplerMode.EXECUTION;
    private double samplingInterval = -1;
    private boolean ignoreSleeping = false;
    private boolean ignoreNative = false;
    private boolean useAsyncProfiler = true;
    private boolean allocLiveOnly = false;
    private long autoEndTime = -1;
    private boolean background = false;
    private ThreadDumper threadDumper = ThreadDumper.ALL;
    private ThreadGrouper threadGrouper = ThreadGrouper.BY_NAME;

    private int ticksOver = -1;
    private TickHook tickHook = null;

    public SamplerBuilder() {
    }

    public SamplerBuilder mode(SamplerMode mode) {
        this.mode = mode;
        return this;
    }

    public SamplerBuilder samplingInterval(double samplingInterval) {
        this.samplingInterval = samplingInterval;
        return this;
    }

    public SamplerBuilder completeAfter(long timeout, TimeUnit unit) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout > 0");
        }
        this.autoEndTime = System.currentTimeMillis() + unit.toMillis(timeout);
        return this;
    }

    public SamplerBuilder background(boolean background) {
        this.background = background;
        return this;
    }

    public SamplerBuilder threadDumper(ThreadDumper threadDumper) {
        this.threadDumper = threadDumper;
        return this;
    }

    public SamplerBuilder threadGrouper(ThreadGrouper threadGrouper) {
        this.threadGrouper = threadGrouper;
        return this;
    }

    public SamplerBuilder ticksOver(int ticksOver, TickHook tickHook) {
        this.ticksOver = ticksOver;
        this.tickHook = tickHook;
        return this;
    }

    public SamplerBuilder ignoreSleeping(boolean ignoreSleeping) {
        this.ignoreSleeping = ignoreSleeping;
        return this;
    }

    public SamplerBuilder ignoreNative(boolean ignoreNative) {
        this.ignoreNative = ignoreNative;
        return this;
    }

    public SamplerBuilder forceJavaSampler(boolean forceJavaSampler) {
        this.useAsyncProfiler = !forceJavaSampler;
        return this;
    }

    public SamplerBuilder allocLiveOnly(boolean allocLiveOnly) {
        this.allocLiveOnly = allocLiveOnly;
        return this;
    }

    public Sampler start(SparkPlatform platform) throws UnsupportedOperationException {
        if (this.samplingInterval <= 0) {
            throw new IllegalArgumentException("samplingInterval = " + this.samplingInterval);
        }

        boolean onlyTicksOverMode = this.ticksOver != -1 && this.tickHook != null;
        boolean canUseAsyncProfiler = this.useAsyncProfiler &&
                !onlyTicksOverMode &&
                !(this.ignoreSleeping || this.ignoreNative) &&
                AsyncProfilerAccess.getInstance(platform).checkSupported(platform);

        boolean canUseAsyncAllocation = this.mode == SamplerMode.ALLOCATION &&
                canUseAsyncProfiler &&
                AsyncProfilerAccess.getInstance(platform).checkAllocationProfilingSupported(platform);

        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        boolean canUseWindowsJfrAllocation = this.mode == SamplerMode.ALLOCATION &&
                isWindows &&
                JfrAllocationSampler.isSupported();

        if (this.mode == SamplerMode.ALLOCATION && !canUseAsyncAllocation && !canUseWindowsJfrAllocation) {
            throw new UnsupportedOperationException("Allocation profiling is not supported on your system. Check the console for more info.");
        }

        if (this.mode == SamplerMode.ALLOCATION && !canUseAsyncAllocation && this.allocLiveOnly) {
            throw new UnsupportedOperationException("--alloc-live-only is not supported by the Windows JFR allocation backend. Use --alloc instead.");
        }

        int interval = (int) (this.mode == SamplerMode.EXECUTION ?
                this.samplingInterval * 1000d :
                this.samplingInterval
        );

        SamplerSettings settings = new SamplerSettings(interval, this.threadDumper, this.threadGrouper, this.autoEndTime, this.background);

        Sampler sampler;
        if (this.mode == SamplerMode.ALLOCATION) {
            if (canUseAsyncAllocation) {
                sampler = new AsyncSampler(platform, settings, new SampleCollector.Allocation(interval, this.allocLiveOnly));
            } else {
                sampler = new JfrAllocationSampler(platform, settings);
            }
        } else if (canUseAsyncProfiler) {
            sampler = new AsyncSampler(platform, settings, new SampleCollector.Execution(interval));
        } else if (onlyTicksOverMode) {
            sampler = new JavaSampler(platform, settings, this.ignoreSleeping, this.ignoreNative, this.tickHook, this.ticksOver);
        } else {
            sampler = new JavaSampler(platform, settings, this.ignoreSleeping, this.ignoreNative);
        }

        sampler.start();
        return sampler;
    }
}
