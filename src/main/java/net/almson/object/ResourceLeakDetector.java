/*
 * Copyright 2013 The Netty Project
 * Copyright 2018 Aleksandr Dubinsky
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package net.almson.object;

import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Detects leaks in ReferenceCountedObjects using WeakReferences.
 * Set the leak detection level using the Java system property {@code -DleakDetection.level}.
 * Take a look at the documentation of each value of {@link Level}.
 * The default level is {@code FULL}.
 */
@lombok.extern.slf4j.Slf4j
public class ResourceLeakDetector {

    static final String PROP_LEVEL = "leakDetection.level";
    static final String PROP_SAMPLING_INTERVAL = "leakDetection.samplingInterval";
    static final String PROP_TRACE_COUNT = "leakDetection.traceCount";
    
    static final Level DEFAULT_LEVEL = Level.FULL;
    static final int DEFAULT_LIGHT_SAMPLING_INTERVAL = 128;
    static final int DEFAULT_TRACE_COUNT = 4;

    /**
     * Represents the level of resource leak detection.
     * Set the leak detection level using the Java system property {@code -DleakDetection.level}.
     * Valid values are {@link #DISABLED DISABLED}, {@link #LIGHT LIGHT}, {@link #FULL FULL}, and {@link #DEBUG DEBUG}.
     * The default level is {@code FULL}.
     */
    public static enum Level {
        /**
         * Disables resource leak detection.
         */
        DISABLED,
        /**
         * Enables sampling leak detection. 
         * Every Nth allocated ReferenceCountedObject has a finalizer attached
         * which checks that it has been destroyed (reference count is 0).
         * This level has little impact on performance even in applications that allocate many objects.
         * You can control the sampling frequency with {@code -DleakDetection.samplingInterval}.
         * The default value is {@code 128}.
         */
        LIGHT,
        /**
         * Enables leak detection for all objects. 
         * Every allocated ReferenceCountedObject has a finalizer attached
         * which checks that it has been destroyed (reference count is 0).
         */
        FULL,
        /**
         * Enables leak detection with tracing of allocation and use. 
         * Every allocated ReferenceCountedObject has a finalizer attached 
         * which checks that it has been destroyed (reference count is 0).
         * Additionally, the stack trace of its allocation is recorded.
         * You may call the method {@link ReferenceCountedObject#trace()} while using using the object 
         * in order to collect additional stack traces.
         * To minimize memory usage, not every stack trace is stored.
         * You can control the number of stack traces that are stored in memory with {@code -DleakDetection.traceCount}.
         * The default value is {@code 4}.
         * The value {@code 1} will only record the allocation stack trace.
         * A value of {@code 2} or greater will record both the stack trace of allocation and of the last call to {@code trace}.
         * A value of {@code 2} or greater causes a possibly significant performance impact,
         * because every call to {@code trace} will allocate a {@code Throwable}.
         * A value of {@code 2} or greater is not a hard limit on the number of stored stack traces.
         * Instead, additional stack traces will be randomly chosen to be stored according to a back-off strategy,
         * in order to aid debugging.
         * Additionally, you can enable sampling with {@code -DleakDetection.samplingInterval}.
         * The default value is {@code 1} (ie, every object is tracked).
         */
        DEBUG;

        static Level valueOfEx (String levelStr) {
            levelStr = levelStr.trim();
            for (Level l : values())
                if (levelStr .equalsIgnoreCase (l.name()) 
                        || levelStr .equals (Integer.toString (l.ordinal()))) 
                    return l;
            
            throw new IllegalArgumentException ("Invalid " + PROP_LEVEL + "=" + levelStr 
                    + ". Acceptable values are DISABLED, LIGHT, FULL, DEBUG, or number 0-3.");
        }
    }

      static ResourceLeakDetector
    newResourceLeakDetector() {
        
            String levelStr = System.getProperty (PROP_LEVEL, DEFAULT_LEVEL.name());
            Level level = Level.valueOfEx (levelStr);

            int samplingInterval, traceCount;
            switch (level)
            {
                case DISABLED:
                    samplingInterval = -1;
                    traceCount = -1;
                    break;
                    
                case LIGHT:
                    samplingInterval = Integer.getInteger (PROP_SAMPLING_INTERVAL, DEFAULT_LIGHT_SAMPLING_INTERVAL);
                    traceCount = 0;
                    break;
                    
                case FULL:
                    samplingInterval = 1;
                    traceCount = 0;
                    break;
                    
                case DEBUG:
                    samplingInterval = Integer.getInteger (PROP_SAMPLING_INTERVAL, 1);
                    traceCount = Integer.getInteger (PROP_TRACE_COUNT, DEFAULT_TRACE_COUNT);
                    break;
                    
                default:
                    throw new AssertionError();
            }

            if (log.isDebugEnabled()) {
                log.debug("-D{}: {}", PROP_LEVEL, level.name().toLowerCase());
                log.debug("-D{}: {}", PROP_TRACE_COUNT, traceCount);
            }
            
            return new ResourceLeakDetector (level, samplingInterval, traceCount);
        }
    
    
    ResourceLeakDetector (Level level, int samplingInterval, int traceCount) {
            
            this.level = level;
            this.samplingInterval = samplingInterval;
            this.traceCount = traceCount;
            
            suppressedStackTraceEntries.add ("net.almson.object.ResourceReference.<init>");
            suppressedStackTraceEntries.add ("net.almson.object.ResourceReference.trace");
            suppressedStackTraceEntries.add ("net.almson.object.ResourceLeakDetector.tryRegister");
            suppressedStackTraceEntries.add ("net.almson.object.ReferenceCountedObject.<init>");
            suppressedStackTraceEntries.add ("net.almson.object.ReferenceCountedObject.trace");
        }

    private final Level level;
    private final int samplingInterval;
    private final int traceCount;
    private final Set<String> suppressedStackTraceEntries = ConcurrentHashMap.newKeySet();

    private final ResourceReference refListHead = new ResourceReference();
    private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private final Set<String> loggedLeaks = ConcurrentHashMap.newKeySet();
    
    // Note sure if these should be exposed. They're pretty happy being final.
//      public void
//    setLevel (Level level) { LEVEL = level; }
//    
//      public void
//    setSamplingInterval (int samplingInterval) { SAMPLING_INTERVAL = samplingInterval; }
//    
//      public void
//    setTraceCount (int traceCount) { TRACE_COUNT = traceCount; }
//
//      public void 
//    suppressStackTraceEntries (Class clz, String ... methodNames) {
//        
//            Set<String> missingMethods = new HashSet<> (Arrays.asList (methodNames));
//            for (Method method : clz.getDeclaredMethods()) 
//                missingMethods.remove (method.getName());
//            if (! missingMethods.isEmpty())
//                throw new IllegalArgumentException("Can't find '" + missingMethods + "' in " + clz.getName());
//
//            for (String methodName : methodNames)
//                suppressedStackTraceEntries.add(clz.getName() + "." + methodName);
//        }

      /**
       * Creates a new {@link ResourceReference} which is expected to be closed via {@link ResourceReference#close()} when the
       * related resource is deallocated.
       *
       * @return the {@link ResourceLeak} or {@code null}
       */
      ResourceReference 
    tryRegister(Object obj) {

            if (level == Level.DISABLED)
                return null;

            if (samplingInterval == 1 || ThreadLocalRandom.current().nextInt(samplingInterval) == 0)
            {
                pollAndLogLeaks();
                return new ResourceReference (obj, referenceQueue, refListHead, traceCount, suppressedStackTraceEntries);
            }
            else
                return null;
        }

      /** Detect and log leaks. */
      public void 
    pollAndLogLeaks() {

            ResourceReference ref;
            while ((ref = (ResourceReference) referenceQueue.poll()) != null) 
            {
                if (!ref.close())
                    continue;
                
                logLeak (ref);
            }
        }
    
      protected void
    logLeak (ResourceReference ref) {
        
            if (!log.isErrorEnabled())
                return;
        
            String leakWarning = getLeakWarning(ref);
            if (loggedLeaks.add (leakWarning))
                log.error (leakWarning);
        }

      protected String 
    getLeakWarning(ResourceReference ref) {
        
            if (level == Level.DEBUG)
            {
                String traces = ref.getTracesString();
                return "RESOURCE LEAK DETECTED: Object of type " + ref.getReferentClassName() + " was not destroyed prior to becoming unreachable and garbage collected. "
                        + "See the documentation of net.almson.object.ReferenceCountedObject."
                        + traces
                        + (traceCount == 0 ? System.lineSeparator() + "\tStack traces are not being stored. To store allocation stack traces specify the JVM option -D"+PROP_TRACE_COUNT+"=1 or greater." : "")
                        + (traceCount == 1 ? System.lineSeparator() + "\tOnly the allocation stack trace was stored. To store additional stack traces specify the JVM option -D"+PROP_TRACE_COUNT+"=2 or greater." : "")
                        + (traceCount >= 2 ? System.lineSeparator() + "\tTo trace the lifetime of the object more thoroughly, make more frequent calls to ReferenceCountedObject.trace()." : "");
            }
            else
                return "RESOURCE LEAK DETECTED: Object of type " + ref.getReferentClassName() + " was not destroyed prior to becoming unreachable and garbage collected. "
                        + "See the documentation of net.almson.object.ReferenceCountedObject. "
                        + "The log level is " + level + ", which does not record stack traces. "
//                        + "To enable debugging, specify the JVM option '-D"+PROP_LEVEL+"="+Level.DEBUG.name().toLowerCase()+"' or call ResourceLeakDetector.setLevel().";
                        + "To enable debugging, specify the JVM option -D"+PROP_LEVEL+"="+Level.DEBUG;
        }
}
