/*
 * Copyright 2018 Aleksandr Dubinsky
 *
 * Aleksandr Dubinsky licenses this file to you under the Apache License,
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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

  /**
   * A base class for closeable objects with support for leak detection but not reference counting.
   * 
   * <p> This base class provides the leak detection functionality of {@link ReferenceCountedObject}
   * and {@link ResourceLeakDetector}, but does not offer {@code retain} and {@code release} methods.
   * 
   * <p> This class does not offer any functional or performance benefits,
   * and the choice to use it instead of {@code ReferenceCountedObject} is mainly esthetic. 
   * The API offered by this class is simpler and more familiar to Java programmers.
   */
  public
abstract class CloseableObject implements AutoCloseable {
      
      public static final ResourceLeakDetector
//    LEAK_DETECTOR = ResourceLeakDetectorFactory.instance().newResourceLeakDetector();
    LEAK_DETECTOR = ResourceLeakDetector.newResourceLeakDetector();

      private static final AtomicIntegerFieldUpdater<CloseableObject> 
    REFERENCE_COUNT_UPDATER = AtomicIntegerFieldUpdater.newUpdater (CloseableObject.class, "referenceCount");
      
//      /**
//       * Equivalent to calling {@link #ReferenceCountedObject(boolean) this(boolean)} with a value of {@code false}.
//       */
//      protected
//    CloseableObject() { this (false); }
//    
//      /**
//       * @param idempotentClose if {@code true}, do not throw an exception if {@code close} or {@code release} 
//       *        is called more often than necessary, ie if the reference count becomes negative.
//       *        This is the encouraged behavior of {@link java.lang.AutoCloseable#close()}, 
//       *        although the contract of that method explicitly states that idempotency is not required.
//       *        Nevertheless, passing {@code false} may help detect bugs.
//       *        Logic which will not {@code release} a destroyed object is more likely to be correct overall,
//       *        particularly if the object may be shared.
//       */
//      protected
//    CloseableObject (boolean idempotentClose) {
//        
//            this.idempotentClose = idempotentClose;
//        }
      
    
      private volatile int
    referenceCount = 1;
      
    // idempotentClose is assumed to always be true
    // see javadoc of ReferenceCountedObject(boolean) constructor
//      private final boolean
//    idempotentClose;
    
      private final ResourceReference 
    resourceReference = LEAK_DETECTOR.tryRegister (this);
      
      
      /**
       * Closes this resource, relinquishing any underlying resources.
       * This method is invoked when the reference count reaches {@code 0}.
       * This method will not be invoked more than once.
       *
       * <p> Cases where the close operation may fail require careful
       * attention by implementers. It is strongly advised to relinquish
       * the underlying resources and to internally <em>mark</em> the
       * resource as closed, prior to throwing the exception.
       *
       * <p><em>Implementers of this interface are also strongly advised
       * to not have the {@code destroy} method throw {@link
       * InterruptedException}.</em>
       *
       * This exception interacts with a thread's interrupted status,
       * and runtime misbehavior is likely to occur if an {@code
       * InterruptedException} is {@linkplain Throwable#addSuppressed
       * suppressed}.
       *
       * More generally, if it would cause problems for an
       * exception to be suppressed, the {@code ReferenceCountedObject.destroy}
       * method should not throw it.
       */
      protected abstract void 
    destroy();


//      /**
//       * Increments the reference count by {@code 1}.
//       * 
//       * @return this object
//       */
//      public final CloseableObject 
//    retain() {
//        
//            long oldCount = REFERENCE_COUNT_UPDATER.getAndIncrement (this);
//            
//            if (oldCount <= 0)
//            {
//                REFERENCE_COUNT_UPDATER.getAndDecrement (this); // not exactly safe, but better than nothing
//                
//                throw new AssertionError ("Resurrected a destroyed object" 
//                        + (resourceReference != null ? resourceReference.getTracesString() : ""));
//            }
//            
//            if (resourceReference != null)
//                resourceReference.trace ("Object retained. Reference count is now {}", oldCount + 1);
//            
//            return this;
//        }
    
      /**
       * Decreases the reference count by {@code 1} and calls {@link #destroy} if the reference count reaches
       * {@code 0}.
       *
       * @return {@code true} if the reference count became {@code 0} and this object was destroyed
       */
//      public final boolean 
      private boolean 
    release() {
        
            long newCount = REFERENCE_COUNT_UPDATER.decrementAndGet (this);
        
//            if (resourceReference != null)
//                resourceReference.trace ("Object released. Reference count is now {}.", newCount);
            
            if (newCount == 0) 
            {
                destroy();

                if (resourceReference != null)
                    // Recent versions of the JDK have a nasty habit of prematurely deciding objects are unreachable.
                    // see: https://stackoverflow.com/questions/26642153/finalize-called-on-strongly-reachable-object-in-java-8
                    // The test ResourceLeakDetectorTest.testConcurrentUsage reproduces this issue on JDK 8
                    // if no counter-measures are taken.
                    // The method Reference.reachabilityFence offers a solution to this problem.
                    // However, besides only being available in Java 9+,
                    // it "is designed for use in uncommon situations of premature finalization where using
                    // synchronized blocks or methods [is] not possible or do not provide the desired control."
                    // Because we just destroyed the object,
                    // it is unreasonable that anyone else, anywhere, is hold a lock.
                    // Therefore, it seems using a synchronization block is possible here, so we will use one!
                    synchronized (this)
                    {
                        resourceReference.close();
                    }
                
                return true;
            }
//            else if (!idempotentClose && newCount <= -1) 
//            {
//                throw new AssertionError ("Tried to release a destroyed object"
//                        + (resourceReference != null ? resourceReference.getTracesString() : ""));
//            }
            else
                return false;
        }

//      /** 
//       * {@code close} simply calls {@link #release release}.
//       * The intent is to allow patterns such as:
//       * <pre>
//       * try (new MyReferenceCountedObject())
//       * { ... }
//       * 
//       * try (refCountedObject.retain())
//       * { ... }
//       * 
//       * {@literal @}lombok.Cleanup var a = new MyReferenceCountedObject();
//       * </pre>
//       */
      /**
       * Closes this resource, relinquishing any underlying resources.
       * This method is invoked automatically on objects managed by the
       * {@code try}-with-resources statement.
       * 
       * <p> This method is idempotent. In other words,
       * calling this {@code close} method more than once will not have an effect.
       * The first time this method is called, the object will be destroyed.
       */
      public final @Override void
    close() {
        
            release();
        }

      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must set the {@link ResourceLeakDetector.Level resource leak detector level} 
       * to {@link ResourceLeakDetector.Level#DEBUG DEBUG}.
       */
      public final void 
    trace() {
        
            trace (null);
        }
    
      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must set the {@link ResourceLeakDetector.Level resource leak detector level} 
       * to {@link ResourceLeakDetector.Level#DEBUG DEBUG}.
       * This method follows the SLF4J API.
       */
      public final void
    trace (String format, Object param1) {
        
            assertNotDestroyed();
        
            if (resourceReference != null)
                resourceReference.trace (format, param1);
        }
    
      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must set the {@link ResourceLeakDetector.Level resource leak detector level} 
       * to {@link ResourceLeakDetector.Level#DEBUG DEBUG}.
       * This method follows the SLF4J API.
       */
      public final void
    trace (String format, Object param1, Object param2) {
        
            assertNotDestroyed();
        
            if (resourceReference != null)
                resourceReference.trace (format, param1, param2);
        }
    
      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must set the {@link ResourceLeakDetector.Level resource leak detector level} 
       * to {@link ResourceLeakDetector.Level#DEBUG DEBUG}.
       * This method follows the SLF4J API.
       */
      public final void
    trace (String format, Object... argArray) {
        
            assertNotDestroyed();
        
            if (resourceReference != null)
                resourceReference.trace (format, argArray);
        }
    
      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must set the {@link ResourceLeakDetector.Level resource leak detector level} 
       * to {@link ResourceLeakDetector.Level#DEBUG DEBUG}.
       * This method attempts to have minimal performance impact.
       * @param message a string or object whose {@code toString} method will be called 
       *                 only in the case that a leak is logged and stack traces are enabled
       */
      public final void
    trace (Object message) {
        
            assertNotDestroyed();
        
            if (resourceReference != null)
                resourceReference.trace (message);
        }
    
      /**
       * May be used to assert that the object hasn't been accidentally destroyed.
       * In case of concurrency, this method cannot guarantee that the object has not been destroyed.
       */
      protected final void
    assertNotDestroyed() {
        
            if (referenceCount <= 0)
                throw new AssertionError ("Trying to use a destroyed object.");
        }
}
