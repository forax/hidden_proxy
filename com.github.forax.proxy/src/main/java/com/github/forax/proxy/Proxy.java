package com.github.forax.proxy;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.util.stream.Stream;

import static java.lang.constant.ConstantDescs.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A class that dynamically generates a proxy class that implement several interfaces and
 * delegate to a {@link Proxy.Linker} the actual implementation of each method of the proxy.
 */
public class Proxy {
  /**
   * A linker that resolves the calls to the proxy by providing a method handle for a method of the proxy.
   */
  @FunctionalInterface
  public interface Linker {
    /**
     * Returns a method handle that will be installed inside the proxy for a declared method of the proxy.
     * @param methodInfo the method of the proxy that should be implemented
     * @return a method handle that will be installed inside the proxy for a declared method of the proxy.
     */
    MethodHandle resolve(MethodHandleInfo methodInfo) throws Throwable;
  }

  private Proxy() {
    throw new AssertionError();
  }

  /**
   * Defines a proxy class and returns a lookup on that class.
   *
   * @param lookup the lookup used to define the proxy class.
   * @param interfaces the interfaces implemented by the proxy class.
   * @param shouldOverride a predicate indicating if methods of java.lang.Object or default method
   *                       should be overridden or not
   * @param delegateClass the class of the delegate field inside the proxy or void.class if there is no field.
   * @param linker the linker that will resolve the calls to the proxy methods.
   * @return a new proxy class that implements the interfaces.
   * @throws IllegalAccessException – if this Lookup does not have full privilege access
   * @throws SecurityException – if a security manager is present and it refuses access
   * @throws NullPointerException – if any parameter is null
   */
  public static Lookup defineProxy(Lookup lookup, Class<?>[] interfaces, Predicate<Method> shouldOverride, Class<?> delegateClass, Linker linker) throws IllegalAccessException {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(interfaces);
    Objects.requireNonNull(shouldOverride);
    Objects.requireNonNull(delegateClass);
    Objects.requireNonNull(linker);
    var proxyName = lookup.lookupClass().getPackageName().replace('.', '/') + "/ProxyImpl";
    var bytecode = generateBytecode(proxyName, interfaces, shouldOverride, delegateClass);
    return lookup.defineHiddenClassWithClassData(bytecode, linker, true, ClassOption.NESTMATE, ClassOption.STRONG);
  }

  private static byte[] generateBytecode(String proxyName, Class<?>[] interfaces, Predicate<Method> shouldOverride, Class<?> delegateClass) {
    var interfaceList = List.of(interfaces);
    record Key(String name, String descriptor) { }
    var map = new LinkedHashMap<Key, Method>();
    Stream.concat(Stream.of(Object.class), interfaceList.stream())
        .flatMap(type -> Arrays.stream(type.getMethods()))
        .filter(m -> (m.getModifiers() & (Modifier.FINAL | Modifier.STATIC)) == 0)
        .filter(m -> (m.getDeclaringClass() != Object.class && !m.isDefault()) || shouldOverride.test(m))
        .forEach(m -> map.putIfAbsent(new Key(m.getName(), MethodType.methodType(m.getReturnType(), m.getParameterTypes()).descriptorString()), m));
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    var interfaceNames = interfaceList.stream().map(itf -> itf.getName().replace('.', '/')).toArray(String[]::new);
    writer.visit(V16, ACC_PUBLIC | ACC_SUPER, proxyName, null, "java/lang/Object", interfaceNames);
    if (delegateClass != void.class) {
      var fv = writer.visitField(ACC_PRIVATE | ACC_FINAL, "delegate", delegateClass.descriptorString(), null, null);
      fv.visitEnd();
    }

    var init = writer.visitMethod(ACC_PUBLIC, "<init>", delegateClass == void.class? "()V": "(" + delegateClass.descriptorString() + ")V", null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    if (delegateClass != void.class) {
      var delegateType = Type.getType(delegateClass);
      init.visitVarInsn(ALOAD, 0);
      init.visitVarInsn(delegateType.getOpcode(ILOAD), 1);
      init.visitFieldInsn(PUTFIELD, proxyName, "delegate", delegateClass.descriptorString());
    }
    init.visitInsn(RETURN);
    init.visitMaxs(-1, -1);
    init.visitEnd();

    map.forEach((key, method) -> {
      var descriptor = key.descriptor;
      var mv = writer.visitMethod(ACC_PUBLIC, key.name, descriptor, null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      if (delegateClass != void.class) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, proxyName, "delegate", delegateClass.descriptorString());
      }
      var parameterSlot = 1;
      var parameterTypes = Type.getArgumentTypes(descriptor);
      for (var parameterType : parameterTypes) {
        mv.visitVarInsn(parameterType.getOpcode(ILOAD), parameterSlot);
        parameterSlot += parameterType.getSize();
      }
      var isInterface = method.getDeclaringClass().isInterface(); // it can be java/lang/Object
      var methodHandle = new Handle(isInterface? H_INVOKEINTERFACE: H_INVOKEVIRTUAL, method.getDeclaringClass().getName().replace('.', '/'), method.getName(), descriptor, isInterface);
      var indyDesc = "(Ljava/lang/Object;" + (delegateClass == void.class? "": delegateClass.descriptorString()) + descriptor.substring(1);
      mv.visitInvokeDynamicInsn(method.getName(), indyDesc, BSM, methodHandle);
      mv.visitInsn(Type.getReturnType(descriptor).getOpcode(IRETURN));
      mv.visitMaxs(-1, -1);
      mv.visitEnd();
    });
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static final Handle BSM = new Handle(H_INVOKESTATIC,
      Proxy.class.getName().replace('.', '/'),
      "proxyMetaFactory",
      MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_MethodHandle).descriptorString(),
      false);

  public static CallSite proxyMetaFactory(Lookup lookup, String name, MethodType methodType, MethodHandle mh) throws Throwable {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(name);
    Objects.requireNonNull(methodType);
    Objects.requireNonNull(mh);
    var info = lookup.revealDirect(mh);
    var linker = MethodHandles.classData(lookup, "_", Linker.class);
    var target = linker.resolve(info);
    return new ConstantCallSite(target.asType(methodType));
  }
}
