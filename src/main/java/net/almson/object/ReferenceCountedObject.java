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

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

  /**
   * The base class for reference counted objects.
   */
  public
abstract class ReferenceCountedObject implements AutoCloseable {
      
      public static final ResourceLeakDetector
//    LEAK_DETECTOR = ResourceLeakDetectorFactory.instance().newResourceLeakDetector();
    LEAK_DETECTOR = ResourceLeakDetector.newResourceLeakDetector();

      private static final AtomicLongFieldUpdater<ReferenceCountedObject> 
    REFERENCE_COUNT_UPDATER = AtomicLongFieldUpdater.newUpdater (ReferenceCountedObject.class, "referenceCount");

      
    
      @SuppressWarnings("unused")
      private volatile long 
    referenceCount = 1;
    
      private final ResourceReference 
    resourceReference = LEAK_DETECTOR.tryRegister (this);
      
      
      /**
       * The destructor, called when the reference count reaches {@code 0}.
       * Override this method with your resource-freeing logic.
       */
      protected abstract void 
    destroy();


      /**
       * Increments the reference count by {@code 1}.
       * 
       * @return this object
       */
      public final ReferenceCountedObject 
    retain() {
        
            long oldCount = REFERENCE_COUNT_UPDATER.getAndIncrement (this);
            
            if (oldCount <= 0)
            {
                REFERENCE_COUNT_UPDATER.getAndDecrement (this); // not exactly safe, but better than nothing
                
                throw new AssertionError ("Resurrected a destroyed object" 
                        + (resourceReference != null ? resourceReference.getTracesString() : ""));
            }
            
            if (resourceReference != null)
                resourceReference.trace ("retain");
            
            return this;
        }
    
      /**
       * Decreases the reference count by {@code 1} and calls {@link #destroy} if the reference count reaches
       * {@code 0}.
       *
       * @return {@code true} if the reference count became {@code 0} and this object was destroyed
       */
      public final boolean 
    release() {
        
            if (resourceReference != null)
                resourceReference.trace ("release");
        
            long newCount = REFERENCE_COUNT_UPDATER.decrementAndGet (this);
            
            if (newCount == 0) 
            {
                destroy();

                if (resourceReference != null)
                    // Recent versions of the JDK have a nasty habit of prematurely deciding objects are unreachable.
                    // see: https://stackoverflow.com/questions/26642153/finalize-called-on-strongly-reachable-object-in-java-8
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
            else if (newCount <= -1) 
            {
                throw new AssertionError ("Tried to release a destroyed object"
                        + (resourceReference != null ? resourceReference.getTracesString() : ""));
            }
            else
                return false;
        }

      /** 
       * {@code close} simply calls {@link #release release}.
       * The intent is to allow patterns such as:
       * <pre>
       * try (new MyReferenceCountedObject())
       * { ... }
       * 
       * try (refCountedObject.retain())
       * { ... }
       * 
       * {@literal @}lombok.Cleanup var a = new MyReferenceCountedObject();
       * </pre>
       */
      public final @Override void
    close() {
        
            release();
        }

      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must enable the {@link ResourceLeakDetector resource leak detector}.
       */
      public final void 
    trace() {
        
            trace (null);
        }
    
      /**
       * Records the stack trace for debugging purposes in case this object is detected to have leaked.
       * You must enable the {@link ResourceLeakDetector resource leak detector}.
       * @param message a string or object whose {@code toString} method will be called and recorded
       *                 for debugging purposes
       */
      public final void
    trace (Object message) {
        
            assert referenceCount > 0 : "Trying to use a destroyed object.";
        
            if (resourceReference != null)
                resourceReference.trace (message);
        }
}
