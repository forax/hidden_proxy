package com.github.forax.hiddenproxy;

import static java.lang.invoke.MethodHandles.Lookup.ClassOption.NESTMATE;
import static java.lang.invoke.MethodHandles.Lookup.ClassOption.STRONG;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.lang.reflect.InvocationHandler;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Allow to dynamically define a {@link Class#isHidden() hidden class}, a proxy, that implement an
 * interface.
 *
 * <p>This class is a replacement of {@link java.lang.reflect.Proxy#newProxyInstance(ClassLoader,
 * Class[], InvocationHandler)} with the following characteristics
 *
 * <ol>
 *   <li>The generated proxy is far faster than {@link java.lang.reflect.Proxy} using invokedynamic
 *       and method handles instead of plain old call and {@link java.lang.reflect.Method}.
 *   <li>Unlike {@link java.lang.reflect.Proxy}, the linker is called once per abstract method and
 *       not once for each call.
 *   <li>Unlike {@link java.lang.reflect.Proxy}, it's up to the user code to cache the constructor
 *       provided by {@link #defineProxy(Lookup, Class, Class, InvocationLinker, ProxyOption...)} if
 *       necessary, this implementation doesn't keep the constructor in a cache. li>Unlike {@link
 *       java.lang.reflect.Proxy}, the generated proxy has one field (the delegate) so you can
 *       delegate method calls to another object.
 *   <li>Unlike {@link java.lang.reflect.Proxy}, the proxy class is created as a hidden class in the
 *       same package as the lookup class so if the interface has to be visible by the lookup class
 *       otherwise it will fail.
 * </ol>
 *
 * @see #defineProxy(Lookup, Class, Class, InvocationLinker, ProxyOption...)
 */
public interface HiddenProxy {

  /**
   * A linker of all the methods of an interface. For each method of the proxy interface, the method
   * {@link #link(Lookup, MethodHandleInfo)} is called to get the corresponding implementation as a
   * method handle the first time the abstract method is called. For a default method, the method
   * {@link #overrideDefaultMethod(MethodHandleInfo)} will be called before {@link #link(Lookup,
   * MethodHandleInfo)} to know if the method is overridden by the proxy or not.
   *
   * @see #defineProxy(Lookup, Class, Class, InvocationLinker, ProxyOption...)
   */
  @FunctionalInterface
  interface InvocationLinker {

    /**
     * This method is called once per default method to know if the proxy should override the method
     * or not.
     *
     * @implNote this implementation always return false.
     * @param lookup the lookup pass as parameter of {@link #defineProxy(Lookup, Class, Class,
     *     InvocationLinker, ProxyOption...)}.
     * @param methodInfo a description of the interface method to override.
     */
    default boolean overrideDefaultMethod(Lookup lookup, MethodHandleInfo methodInfo) {
      return false;
    }

    /**
     * This method is called once per method of the interface to link the implementation of the
     * method to a target method handle. The {@link MethodHandle#type()} of the target method handle
     * must be the {@link MethodHandleInfo#getMethodType() type of the method info} with the type of
     * the interface and the delegate {@link java.lang.invoke.MethodType#insertParameterTypes(int,
     * Class[]) inserted} at the position 0 and 1.
     *
     * <p>By example, to link {@link Object#equals(Object)} of an interface {@code Foo} with no
     * delegate, the method info type is {@code (Object)boolean} and the type of the target method
     * is {@code (Fun, Object)boolean}.
     *
     * <p>To link {@link Object#hashCode()} of an interface {@code Foo} with a {@code String} as
     * delegate, the method info type is {@code ()int} and the type of the target method is {@code
     * (Fun, String)int}.
     *
     * @param lookup a lookup object representing the proxy class.
     * @param methodInfo a description of the interface method to implement.
     * @return a method handle (the target) that will be called each time the method of the proxy is
     *     called.
     * @throws ReflectiveOperationException if any reflective operation fails.
     * @see Lookup
     * @see java.lang.invoke.MethodHandles
     */
    MethodHandle link(Lookup lookup, MethodHandleInfo methodInfo)
        throws ReflectiveOperationException;
  }

  /**
   * Options defining the kind of proxy to be defined.
   *
   * @see #defineProxy(Lookup, Class, Class, InvocationLinker, ProxyOption...)
   */
  enum ProxyOption {
    /**
     * The defined proxy class will be garbage collectable only when the classloader will be garbage
     * collectable.
     */
    REGISTER_IN_CLASSLOADER
  }

  /**
   * True if the class taken as parameter is a hidden proxy defined by {@link #defineProxy(Lookup,
   * Class, Class, InvocationLinker, ProxyOption...)}
   *
   * @param type the class to test.
   * @return true if the class taken as parameter is a hidden proxy.
   */
  static boolean isHiddenProxy(Class<?> type) {
    requireNonNull(type);
    return RT.isHiddenProxy(type);
  }

  /**
   * Return the {@link InvocationLinker} associated with the class taken as parameter if the class
   * is an hidden proxy.
   *
   * @param type the class.
   * @return the invocation linker associated to the class if the class is a hidden proxy.
   * @see #isHiddenProxy(Class)
   * @see #defineProxy(Lookup, Class, Class, InvocationLinker, ProxyOption...)
   */
  static Optional<InvocationLinker> getInvocationLinker(Class<?> type) {
    requireNonNull(type);
    return RT.findInvocationLinker(type);
  }

  /**
   * Defines a proxy class that implements an interface in the same package as the lookup and return
   * a constructor to create objects of that interface.
   *
   * <p>For default methods, the proxy first call the method {@link
   * InvocationLinker#overrideDefaultMethod(Lookup, MethodHandleInfo)} to know if the proxy has to
   * provide an implementation or the default method implementation can be used.
   *
   * <p>The first time a method of the interface is called on the proxy class, the {@link
   * InvocationLinker#link(Lookup, MethodHandleInfo)} linker} is called to provide an implementation
   * of that method. All subsequent calls of the same method will always use the implementation
   * provided by the linker.
   *
   * <p>By default, the proxy class is garbage collectable if there is no live instance of the proxy
   * class, so the proxy class is not linked to a peculiar class loader. The option {@link
   * ProxyOption#REGISTER_IN_CLASSLOADER} can be used to associate the proxy class to the class
   * loader of the lookup class.
   *
   * <p>Usage
   *
   * <pre>
   * var constructor = HiddenProxy.defineProxy(MethodHandles.lookup(), Foo.class, linker);
   * var proxy = (Foo)constructor.invoke();
   * </pre>
   *
   * @param lookup the lookup that will be used to create the hidden class, the lookup must have
   *     {@link Lookup#hasFullPrivilegeAccess() full privilege access}.
   * @param interfaceType the interface to implement by the proxy class.
   * @param delegateClass class of the delegate object, it can be void.class if no delegate is
   *     needed.
   * @param linker the linker that will be used to find the implementations of the abstract methods.
   * @param proxyOptions the only option available is {@link ProxyOption#REGISTER_IN_CLASSLOADER}
   *     that ask the proxy class to be associated to a classloader of the lookup class.
   * @return a method handle on a constructor that can be used to create proxy instances.
   * @throws NoClassDefFoundError if the bytecode of the interface is not available. This
   *     restriction may be lifted in the future.
   * @throws IllegalAccessError if the lookup has not {@link Lookup#hasFullPrivilegeAccess() full
   *     privilege access}.
   * @throws IllegalArgumentException if {@code interfaceType} is not an interface or there are
   *     several times the same proxy option.
   * @throws NullPointerException if any parameter is {@code null}.
   */
  static MethodHandle defineProxy(
      Lookup lookup,
      Class<?> interfaceType,
      Class<?> delegateClass,
      InvocationLinker linker,
      ProxyOption... proxyOptions) {
    requireNonNull(lookup);
    requireNonNull(interfaceType);
    requireNonNull(delegateClass);
    requireNonNull(linker);
    requireNonNull(proxyOptions);
    if (!interfaceType.isInterface()) {
      throw new IllegalArgumentException("interfaceType is not an interface " + interfaceType);
    }
    var registerInClassLoader = Set.of(proxyOptions).contains(ProxyOption.REGISTER_IN_CLASSLOADER);

    byte[] byteArray;
    try {
      // check the interface accessibility first to have better error message
      lookup.accessClass(interfaceType);

      byteArray =
          HiddenProxyDetails.generateProxyByteArray(lookup, interfaceType, delegateClass, linker);
    } catch (IllegalAccessException e) { // the lookup can not see the interface / interface methods
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }

    var hiddenClassOptions =
        Stream.of(NESTMATE, STRONG)
            .filter(classOption -> classOption != STRONG || registerInClassLoader)
            .toArray(ClassOption[]::new);
    Lookup hiddenClassLookup;
    try {
      hiddenClassLookup = lookup.defineHiddenClass(byteArray, true, hiddenClassOptions);
    } catch (IllegalAccessException e) { // if the lookup has not full privilege
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }

    var hiddenClass = hiddenClassLookup.lookupClass();
    var constructorReturnType =
        delegateClass == void.class
            ? methodType(void.class)
            : methodType(void.class, delegateClass);
    RT.handshake(hiddenClass, linker);
    try {
      return hiddenClassLookup.findConstructor(hiddenClass, constructorReturnType);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
}
