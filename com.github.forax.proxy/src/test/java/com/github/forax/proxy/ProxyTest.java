package com.github.forax.proxy;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.DoubleSupplier;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.empty;
import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ProxyTest {
  @Test
  public void callSum() throws Throwable {
    var lookup = MethodHandles.lookup();
    var target = lookup.findStatic(Integer.class, "sum", methodType(int.class, int.class, int.class));

    var proxyLookup = Proxy.defineProxy(lookup,
        new Class<?>[] { IntBinaryOperator.class },
        method -> false, // Object methods (toString, equals, hashCode and default method are not overridden
        void.class, // no field
        methodInfo -> dropArguments(target,0, Object.class) // drop the proxy which is the first argument
    );

    var constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(), methodType(void.class));
    var proxy = (IntBinaryOperator) constructor.invoke();
    assertEquals(5, proxy.applyAsInt(2, 3));
  }

  @Test
  public void transparentProxy() throws Throwable {
    interface Foo {
      double m(int i);
      String s(Object o);
    }
    record FooImpl(int x) implements Foo {
      @Override
      public double m(int i) {
        return x + i;
      }
      @Override
      public String s(Object o) {
        return o.toString();
      }
    }

    var lookup = MethodHandles.lookup();
    var proxyLookup = Proxy.defineProxy(lookup,
        new Class<?>[] { Foo.class },
        method -> method.getDeclaringClass() == Object.class, // override Object methods (toString, equals, hashCode)
        Foo.class, // delegate field
        methodInfo -> {
           var method = methodInfo.reflectAs(Method.class, lookup);
           var target = lookup.unreflect(method);
           return dropArguments(target,0, Object.class); // drop the proxy which is the first argument
        }
    );

    var constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(), methodType(void.class, Foo.class));
    var impl = new FooImpl(4);
    var proxy = (Foo) constructor.invoke(impl);
    assertEquals(8.0, proxy.m(4));
    assertEquals("bar", proxy.s("bar"));
    assertEquals(impl.toString(), proxy.toString());
  }

  private static boolean same(Object o1, Object o2) {
    return o1 == o2;
  }

  @Test
  public void defineDelegatingProxyPrimitive() throws Throwable {
    var lookup = MethodHandles.lookup();
    var same = lookup.findStatic(ProxyTest.class, "same", methodType(boolean.class, Object.class, Object.class));
    var linker = (Proxy.Linker)(methodInfo) -> switch(methodInfo.getName()) {
        case "getAsInt", "hashCode" -> dropArguments(MethodHandles.identity(int.class), 0, Object.class);
        case "equals" -> dropArguments(same, 1, int.class);
        case "toString" -> dropArguments(constant(String.class, "proxy"), 0, Object.class, int.class);
        default -> fail("unknown method " + methodInfo);
    };

    var proxyLookup = Proxy.defineProxy(lookup, new Class<?>[] { IntSupplier.class }, __ -> true, int.class, linker);
    var constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(), methodType(void.class, int.class));

    var proxy = (IntSupplier)constructor.invoke(42);
    assertEquals(42, proxy.getAsInt());
    assertTrue(proxy.equals(proxy));
    assertFalse(proxy.equals("foo"));
    assertFalse(proxy.equals(null));
    assertEquals(42, proxy.hashCode());
    assertEquals("proxy", proxy.toString());
  }

  @Test
  public void defineProxyEmptyObjectMethods() throws Throwable {
    var lookup = MethodHandles.lookup();
    var proxyLookup = Proxy.defineProxy(lookup, new Class<?>[] { Runnable.class }, __ -> true, void.class, methodInfo -> {
      switch (methodInfo.getName()) {
        case "equals", "hashCode", "toString" -> { /* OK */ }
        default -> fail();
      }

      // return an empty implementation
      return empty(methodInfo.getMethodType().insertParameterTypes(0, Object.class));
    });
    var constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(), methodType(void.class));
    var proxy = constructor.invoke();
    assertFalse(proxy.equals("not used"));
    assertEquals(0, proxy.hashCode());
    assertNull(proxy.toString());
  }

  @Test
  public void hello() throws Throwable {
    interface HelloProxy {
      String hello(String text);
    }
    class Impl {
      static String impl(int repeated, String text) {
        return text.repeat(repeated);
      }
    }

    Lookup lookup = MethodHandles.lookup();
    MethodHandle impl =  lookup.findStatic(Impl.class, "impl", methodType(String.class, int.class, String.class));
    Proxy.Linker linker = methodInfo -> switch(methodInfo.getName()) {
        case "hello" -> MethodHandles.dropArguments(impl, 0, HelloProxy.class);
        default -> fail("unknown method " + methodInfo);
    };
    Lookup proxyLookup = Proxy.defineProxy(lookup, new Class<?>[] { HelloProxy.class }, __ -> false, int.class, linker);
    MethodHandle constructor = proxyLookup.findConstructor(proxyLookup.lookupClass(), methodType(void.class, int.class));
    HelloProxy proxy = (HelloProxy) constructor.invoke(2);
    assertEquals("proxyproxy", proxy.hello("proxy"));
  }
}