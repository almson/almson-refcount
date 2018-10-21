/*
 * Copyright 2016 The Netty Project
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

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.joor.Reflect;
import org.junit.Assert;

public class ResourceLeakDetectorTest {

    @Test(timeout = 60000)
    public void testConcurrentUsage() throws Throwable {
        
        TestResourceLeakDetector detector = new TestResourceLeakDetector();
        // Hack to modify the LeakDetector. I'm still grappling with whether it should be publically modifiable.
        Reflect.on (ReferenceCountedObject.class).set ("LEAK_DETECTOR", detector);
        
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicReference<Object> error = new AtomicReference<>();
        // With 50 threads issue #6087 is reproducible on every run.
        Thread[] threads = new Thread[50];
        final CyclicBarrier barrier = new CyclicBarrier(threads.length);
        for (int i = 0; i < threads.length; i++) {
            Thread t = new Thread(new Runnable() {
                Queue<ReferenceCountedObject> resources = new ArrayDeque<>(100);

                @Override
                public void run() {
                    try {
                        barrier.await();

                        // Run 10000 times or until the test is marked as finished.
                        for (int b = 0; b < 1000 && !finished.get(); b++) {

                            // Allocate 100 LeakAwareResource per run and close them after it.
                            for (int a = 0; a < 100; a++) {
                                ReferenceCountedObject leakAwareResource = new ReferenceCountedObject() { @Override protected void destroy() { }};
                                leakAwareResource.trace ("We're adding it to 'resources'");
                                resources.add(leakAwareResource);
                            }
                            if (closeResources(true)) {
                                finished.set(true);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        // Just close all resource now without assert it to eliminate more reports.
                        closeResources(false);
                    }
                }

                private boolean closeResources(boolean checkClosed) {
                    for (;;) {
                        ReferenceCountedObject r = resources.poll();
                        if (r == null) {
                            return false;
                        }
                        r.trace ("We just retrieved it from 'resources'");
                        boolean closed = r.release();
                        if (checkClosed && !closed) {
                            error.compareAndSet(null, "ResourceLeak.close() returned 'false' but expected 'true'");
                            return true;
                        }
                    }
                }
            });
            threads[i] = t;
            t.start();
        }

        // Just wait until all threads are done.
        for (Thread t: threads) {
            t.join();
        }

        // Check if we had any leak reports in the ResourceLeakDetector itself
        detector.assertNoErrors();

        if (error.get() != null)
            Assert.fail (error.get().toString());
    }

    private static final class TestResourceLeakDetector extends ResourceLeakDetector {
        
//        TestResourceLeakDetector() { super (Level.FULL, 1, 0); }
//        TestResourceLeakDetector() { super (Level.DEBUG, 1, 1); }
//        TestResourceLeakDetector() { super (Level.DEBUG, 1, 2); }
        TestResourceLeakDetector() { super (Level.DEBUG, 1, 10); }

        private final AtomicReference<String> error = new AtomicReference<>();

        @Override
        protected void logLeak (ResourceReference ref) {
            reportError(super.getLeakWarning(ref));
        }

        private void reportError(String cause) {
            error.compareAndSet(null, cause);
        }

        void assertNoErrors() throws Throwable {
            if (error.get() != null)
                Assert.fail (error.get());
        }
    }
}
