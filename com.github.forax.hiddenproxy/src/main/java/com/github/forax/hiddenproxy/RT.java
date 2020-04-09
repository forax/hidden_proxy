package com.github.forax.hiddenproxy;

import com.github.forax.hiddenproxy.HiddenProxy.InvocationLinker;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.util.Objects;
import java.util.Optional;

public class RT {
  private RT() {
    throw new AssertionError();
  }

  private static final ThreadLocal<InvocationLinker> HANDSHAKE = new ThreadLocal<>();
  private static final ClassValue<InvocationLinker>  LINKERS = new ClassValue<>() {
    @Override
    protected InvocationLinker computeValue(Class<?> type) {
      var linker = HANDSHAKE.get();
      if (linker == null) {
        throw new IllegalStateException("a hidden proxy was not created by HiddenProxy ?");
      }
      return linker;
    }
  };

  static void handshake(Class<?> type, InvocationLinker linker) {
    HANDSHAKE.set(linker);
    try {
      var result = LINKERS.get(type);
      if (result != linker) {
        throw new AssertionError("result != linker");
      }
    } finally {
      HANDSHAKE.remove();
    }
  }

  static boolean isHiddenProxy(Class<?> type) {
    return type.isHidden() && HiddenProxy.class.isAssignableFrom(type);
  }

  static Optional<InvocationLinker> findInvocationLinker(Class<?> type) {
    if (!isHiddenProxy(type)) {
      return Optional.empty();
    }
    return Optional.of(LINKERS.get(type));
  }

  /**
   * This method is accidentally public and used by any proxy to find its corresponding linker.
   * Future version of this API will remove that method.
   *
   * @param lookup
   * @param name
   * @param methodType
   * @param abstractMethodType
   * @return
   * @throws ReflectiveOperationException
   *
   * @deprecated future version of this API will remove that method.
   */
  @Deprecated(forRemoval = true)
  public static CallSite bsm(Lookup lookup, String name, MethodType methodType, MethodType abstractMethodType)
      throws ReflectiveOperationException {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(name);
    Objects.requireNonNull(methodType);
    Objects.requireNonNull(abstractMethodType);

    var proxyClass = lookup.lookupClass();
    var interfaceType = abstractMethodType.parameterType(0);
    var mh = lookup.findVirtual(interfaceType, name, abstractMethodType.dropParameterTypes(0, 1));
    var info = lookup.revealDirect(mh);
    var linker = LINKERS.get(proxyClass);
    var target = linker.link(lookup, info);

    Objects.requireNonNull(target, "null target");
    var targetType = target.type();
    if (targetType.parameterCount() != methodType.parameterCount()) {
      throw new WrongMethodTypeException("target type has wrong number of arguments " + targetType + " but should be " + methodType);
    }

    return new ConstantCallSite(target.asType(methodType));
  }
}
