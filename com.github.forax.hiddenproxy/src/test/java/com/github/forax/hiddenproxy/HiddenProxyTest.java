package com.github.forax.hiddenproxy;

import static java.lang.invoke.MethodHandleInfo.REF_invokeInterface;
import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isAbstract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.forax.hiddenproxy.HiddenProxy.InvocationLinker;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.time.LocalDate;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

public class HiddenProxyTest {

  interface Foo {

    int bar(double d, String s);
  }

  @Test
  public void isHiddenProxy() throws Throwable {
    var empty = MethodHandles.empty(methodType(int.class, Foo.class, double.class, String.class));
    var constructor = HiddenProxy.defineProxy(MethodHandles.lookup(), Foo.class, void.class,
        (lookup, methodInfo) -> empty);
    var proxy = (Foo) constructor.invoke();
    assertTrue(HiddenProxy.isHiddenProxy(proxy.getClass()));
    assertFalse(HiddenProxy.isHiddenProxy(String.class));
  }

  @Test
  public void getInvocationLinker() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      return MethodHandles.empty(methodType(int.class, Foo.class, double.class, String.class));
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), Foo.class, void.class, linker);
    var proxy = (Foo) constructor.invoke();
    assertEquals(linker, HiddenProxy.getInvocationLinker(proxy.getClass()).orElseThrow());
  }

  @Test
  public void defineProxyOfFoo() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      assertTrue(lookup.lookupClass().isHidden());
      assertEquals(REF_invokeInterface, methodInfo.getReferenceKind());
      assertEquals(Foo.class, methodInfo.getDeclaringClass());
      assertEquals("bar", methodInfo.getName());
      assertEquals(methodType(int.class, double.class, String.class), methodInfo.getMethodType());
      assertTrue(isAbstract(methodInfo.getModifiers()));
      assertFalse(methodInfo.isVarArgs());

      // return an empty implementation
      var targetType = methodInfo.getMethodType()
          .insertParameterTypes(0, Foo.class, LocalDate.class);
      return MethodHandles.empty(targetType);
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), Foo.class, LocalDate.class, linker);
    assertTrue(constructor.type().returnType().isHidden());

    var proxy = (Foo) constructor.invoke((LocalDate) null);
    assertEquals(0, proxy.bar(3.0, "baz"));
  }

  @Test
  public void defineProxyOfFooObjectMethod() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      switch (methodInfo.getName()) {
        case "equals", "hashCode", "toString" -> { /* OK */ }
        default -> fail();
      }

      // return an empty implementation
      return MethodHandles
          .empty(methodInfo.getMethodType().insertParameterTypes(0, Foo.class, Foo.class));
    };
    var constructor = HiddenProxy.defineProxy(MethodHandles.lookup(), Foo.class, Foo.class, linker);
    var proxy = (Foo) constructor.invoke((Foo) null);
    assertFalse(proxy.equals("not used"));
    assertEquals(0, proxy.hashCode());
    assertNull(proxy.toString());
  }

  @Test
  public void defineProxyOfIntBinaryOperator() throws Throwable {
    var sum = MethodHandles.lookup()
        .findStatic(Integer.class, "sum", methodType(int.class, int.class, int.class));
    var target = dropArguments(sum, 0, IntBinaryOperator.class);

    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      assertTrue(lookup.lookupClass().isHidden());
      assertEquals(REF_invokeInterface, methodInfo.getReferenceKind());
      assertEquals(IntBinaryOperator.class, methodInfo.getDeclaringClass());
      assertEquals("applyAsInt", methodInfo.getName());
      assertEquals(methodType(int.class, int.class, int.class), methodInfo.getMethodType());
      assertTrue(isAbstract(methodInfo.getModifiers()));
      assertFalse(methodInfo.isVarArgs());
      return target;
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), IntBinaryOperator.class, void.class, linker);
    assertTrue(constructor.type().returnType().isHidden());

    var proxy = (IntBinaryOperator) constructor.invoke();
    assertEquals(8, proxy.applyAsInt(3, 5));
  }

  @FunctionalInterface
  interface Hello {
    String message(String text);
  }

  @Test
  public void defineDelegatingProxy() throws Throwable {
    var delegate = (Hello)text -> "* " + text + " *";
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      var target = lookup.findVirtual(methodInfo.getDeclaringClass(), methodInfo.getName(), methodInfo.getMethodType());
      return dropArguments(target, 0, Hello.class);
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), Hello.class, Hello.class, linker);
    var proxy = (Hello)constructor.invoke(delegate);
    assertEquals("* hello *", proxy.message("hello"));
    assertEquals(delegate.equals(delegate), proxy.equals(delegate));
    assertEquals(delegate.equals("foo"), proxy.equals("foo"));
    assertEquals(delegate.equals(null), proxy.equals(null));
    assertEquals(delegate.hashCode(), proxy.hashCode());
    assertEquals(delegate.toString(), proxy.toString());
  }

  private static boolean same(Object o, Object o2) {
    return o == o2;
  }
  @Test
  public void defineDelegatingProxyPrimitive() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      return switch(methodInfo.getName()) {
        case "getAsInt", "hashCode" -> dropArguments(MethodHandles.identity(int.class), 0, IntSupplier.class);
        case "equals" -> dropArguments(
            lookup.findStatic(HiddenProxyTest.class, "same", methodType(boolean.class, Object.class, Object.class)),
            1, int.class);
        case "toString" -> dropArguments(constant(String.class, "proxy"), 0, IntSupplier.class, int.class);
        default -> fail("unknown method " + methodInfo);
      };
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), IntSupplier.class, int.class, linker);
    var proxy = (IntSupplier)constructor.invoke(42);
    assertEquals(42, proxy.getAsInt());
    assertTrue(proxy.equals(proxy));
    assertFalse(proxy.equals("foo"));
    assertFalse(proxy.equals(null));
    assertEquals(42, proxy.hashCode());
    assertEquals("proxy", proxy.toString());
  }

  interface Bar {
    int compute(int value);

    default int multiplyBy2(int value) {
      return compute(value) * 2;
    }
  }
  @Test
  public void defineProxyDefaultMethod() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      return switch(methodInfo.getName()) {
        case "compute" -> dropArguments(MethodHandles.identity(int.class), 0, methodInfo.getDeclaringClass());
        default -> fail("unknown method " + methodInfo);
      };
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), Bar.class, void.class, linker);
    var proxy = (Bar)constructor.invoke();
    assertEquals(84, proxy.multiplyBy2(42));
  }

  interface HelloProxy {
    String hello(String text);
  }
  static class Impl {
    static String implementation(int repeated, String text) {
      return text.repeat(repeated);
    }
  }
  @Test
  public void defineProxySimpleDelegation() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> {
      return switch(methodInfo.getName()) {
        case "hello" -> dropArguments(
            lookup.findStatic(Impl.class, "implementation", methodType(String.class, int.class, String.class)),
            0, HelloProxy.class);
        default -> fail("unknown method " + methodInfo);
      };
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), HelloProxy.class, int.class, linker);
    var proxy = (HelloProxy)constructor.invoke(2);
    assertEquals("proxyproxy", proxy.hello("proxy"));
  }

  interface InterfaceWithDefaultMethod {
    default int defaultFoo() { return 64; }
  }

  @Test
  public void defineProxyDoNotOverrideDefaultMethod() throws Throwable {
    var linker = (InvocationLinker)(lookup, methodInfo) -> fail();
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), InterfaceWithDefaultMethod.class, void.class, linker);
    var proxy = (InterfaceWithDefaultMethod)constructor.invoke();
    assertEquals(64, proxy.defaultFoo());
  }

  @Test
  public void defineProxyDoOverrideDefaultMethod() throws Throwable {
    var linker = new InvocationLinker() {
      @Override
      public boolean overrideDefaultMethod(MethodHandleInfo methodInfo) {
        assertEquals(InterfaceWithDefaultMethod.class, methodInfo.getDeclaringClass());
        assertEquals("defaultFoo", methodInfo.getName());
        assertEquals(methodType(int.class), methodInfo.getMethodType());
        return true;
      }

      @Override
      public MethodHandle link(Lookup lookup, MethodHandleInfo methodInfo) {
        assertEquals(InterfaceWithDefaultMethod.class, methodInfo.getDeclaringClass());
        assertEquals("defaultFoo", methodInfo.getName());
        assertEquals(methodType(int.class), methodInfo.getMethodType());
        return dropArguments(constant(int.class, 42), 0, methodInfo.getDeclaringClass());
        }
    };
    var constructor = HiddenProxy
        .defineProxy(MethodHandles.lookup(), InterfaceWithDefaultMethod.class, void.class, linker);
    var proxy = (InterfaceWithDefaultMethod)constructor.invoke();
    assertEquals(42, proxy.defaultFoo());
  }
}