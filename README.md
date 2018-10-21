# Introduction

`almson-refcount` is a reference counting implementation for Java derived from [Netty](netty.io). It aims to be robust, simple, efficient, and clever.

Reference counting is like a more flexible version of `AutoCloseable`. It allows you to do manual, deterministic resource management while letting you pass your resources between objects and methods without deciding on a chain of ownership. Each object has a "reference count" which is initially set to 1. The `close` or `release` method (they both do the same thing) decrements the reference count. When the reference count reaches 0, the object's `destroy` method is called, performing any necessary cleanup. What makes reference counting different from AutoCloseable is the `retain` method, which increments the reference count. Call `retain` on objects which someone else might destroy. This way, the object won't be destroyed until both of you call `close`. Make sure the number of `close/release` calls is 1 more than the number of calls to `retain` by the time everyone is done using the object, or you will cause a resource leak! Thankfully, you will be warned when you call `close/release` too often or too little.

Unfortunately, successfully applying reference counting can be a little tricky. You need some rules by which retain and release are called, and you have to avoid reference cycles. You may want to read up about reference counting to understand this better. However, suffice it to say that it is fairly easy to use reference counting for only a few resource-holding objects, as opposed to using it for _all_ objects (as was the case in Objective-C). The rules can be looser and you can not worry about reference cycles.

# Features

The basic reference counting functionality is simple and straightforward. There is a single base class, `ReferenceCountedObject`. It has a single overrideable method, `destroy`. It provides `retain` and `release` which manage an internal reference counter using a thread-safe and efficient AtomicFieldUpdater. `release` will call `destroy` on the same thread, and because of memory ordering semantics between different calls to `release`, you shouldn't need to worry about the thread-safety of your `destroy` even in a multi-threaded application. The class implements `AutoCloseable` and provides a method `close` which simply calls `release`. This allows it to be used in try-with-resources.

There is no finalization mechanism which tries to call `destroy` in case you forget to call release! Finalization presents big challenges, including concurrency issues and even premature finalization, especially in the general case. (If you insist on having finalizers, you can still use them or the higher-performance `java.lang.ref.Cleaner`.)

Instead, there is a clever leak detection system. It uses a similar mechanism to finalization, however because its only responsibility is detecting leaks and recording debugging info, there is nothing you need to do to make it work correctly.

# Example

    import net.almson.object.ReferenceCountedObject;

    public class MyResource extends ReferenceCountedObject
    {
        InputStream stream = ...;

        public @Override void destroy() { 
            try {
                stream.close() 
            }
            catch (IOException e) {
                throw new UncheckedIOException (e);
            }
        }
    }


    // Then, somewhere:

    try (MyResource r = new MyResource()) // reference count is 1
    {
        ResourceUser user = ...
        user.take (r);
        // reference count is now 2
    } 
    // reference count is now 1


    // ResourceUser is a class that saves the resource for itself:

    public class ResourceUser
    {
        public void take (Resource resource) {

            myResource = resource.retain(); // reference count is 2

            // Don't forget to release it later!
        }

        final MyResource myResource;
    }

# Leak detection

The `ResourceLeakDetector` class manages the leak detection mechanism. It has several options, primarily `level`. Currently, these need to be set as JDK options. To enable the highest level of leak detection, pass `java -DleakDetection.level=DEBUG` when launching your application. The default level is `FULL`.

The available levels are:

- DISABLED - **Disables resource leak detection.**
- LIGHT - **Enables sampling leak detection.** Every Nth allocated ReferenceCountedObject has a finalizer attached which checks that it has been destroyed (reference count is 0).
This level has little impact on performance even in applications that allocate many objects. You can control the sampling frequency with `-DleakDetection.samplingInterval`.
The default value is `128`.
- FULL - **Enables leak detection for all objects.** Every allocated ReferenceCountedObject has a finalizer attached which checks that it has been destroyed (reference count is 0).
- DEBUG - **Enables leak detection with tracing of allocation and use.** Every allocated ReferenceCountedObject has a finalizer attached 
which checks that it has been destroyed (reference count is 0).
Additionally, the stack trace of its allocation is recorded.
You may call the method `ReferenceCountedObject#trace()` while using using the object 
in order to collect additional stack traces.
To minimize memory usage, not every stack trace is stored.
You can control the number of stack traces that are stored in memory with `-DleakDetection.traceCount`.
The default value is `4`.
The value `1` will only record the allocation stack trace.
A value of `2` or greater will record both the stack trace of allocation and of the last call to `trace`.
A value of `2` or greater causes a possibly significant performance impact,
because every call to `trace` will allocate a `Throwable`.
A value of `2` or greater is not a hard limit on the number of stored stack traces.
Instead, additional stack traces will be randomly chosen to be stored according to a back-off strategy,
in order to aid debugging.
Additionally, you can enable sampling with `-DleakDetection.samplingInterval`.
The default value is `1` (ie, every object is tracked).

To make full use of level `DEBUG`, you may insert calls to `trace()` in your code.

Leaks will be logged when new ReferenceCountedObjects are registered with the leak detector, or when `ReferenceCountedObject.LEAK_DETECTOR.pollAndLogLeaks` is called. There is no built-in background thread that will do the polling.

The library uses the SLF4J logging wrapper.