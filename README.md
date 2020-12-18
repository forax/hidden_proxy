# hidden_proxy
A simple API that shows how,to use Hidden class to create proxies.

This is a replacement of `java.lang.reflect.Proxy` API of Java using a more modern design leading to must faster proxies implementation. See the javadoc of `Proxy` for more information.
 
This code is using the Hidden Class API [JEP 371](https://openjdk.java.net/jeps/371) that was introduced in java 15,
so it requires Java 16 to run.

## Example

Let say we have an interface `HelloProxy` with an abstract method `hello` and a static method `implementation` somewhere else
```java
  interface HelloProxy {
    String hello(String text);
  }
  static class Impl {
    static String implementation(int repeated, String text) {
      return text.repeat(repeated);
    }
  }
```

The method `Proxy.defineProxy(lookup, interfaces, overriden, field, linker)` let you dynamically create a class
that implements the interface `HelloProxy` with a field (here an int).
The return value of `defineProxy` is a lookup you can use to find the constructor and
invoke it invoke with the value of the field.
Then the first time an abstract method of the interface is called, here when calling `proxy.hello("proxy")`,
the linker is called to ask how the abstract method should be implemented.
Here the linker will use the method `impl` and discard (using `dropArguments`) the first argument (the proxy)
before calling the method `impl`.
So when calling the method `hello`, the method `impl` will be called with the field stored inside the proxy
as first argument followed by the arguments of the method `hello``.
```java
  Lookup lookup = MethodHandles.lookup();
  MethodHandle impl = lookup.findStatic(Impl.class, "impl",
                                        methodType(String.class, int.class, String.class));
  Proxy.Linker linker = methodInfo -> switch(methodInfo.getName()) {
    case "hello" -> MethodHandles.dropArguments(impl, 0, HelloProxy.class);
    default -> fail("unknown method " + methodInfo);
  };
  Lookup proxyLookup = Proxy.defineProxy(lookup,
    new Class<?>[] { HelloProxy.class },   // proxy interfaces
    __ -> false,                           // don't override toString, equals and hashCode
    int.class,                             // proxy field
    linker);
  MethodHandle constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(),
                                                         methodType(void.class, int.class));
  HelloProxy proxy = (HelloProxy) constructor.invoke(2);
  assertEquals("proxyproxy", proxy.hello("proxy"));
```

If you want more examples, you can take a look to the test class `ProxyTest`.

