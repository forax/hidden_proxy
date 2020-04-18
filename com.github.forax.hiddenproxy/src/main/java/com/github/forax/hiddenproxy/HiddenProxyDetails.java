package com.github.forax.hiddenproxy;

import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.invoke.MethodType.methodType;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V15;

import java.io.IOException;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Modifier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

class HiddenProxyDetails {

  private static final Handle BSM = new Handle(H_INVOKESTATIC,
      RT.class.getName().replace('.', '/'),
      "bsm",
      MethodTypeDesc
          .of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_MethodType)
          .descriptorString(),
      false);
  private static final String HIDDEN_PROXY_NAME = HiddenProxy.class.getName().replace('.', '/');

  static String proxyName(Class<?> lookupClass) {
    return lookupClass.getName().replace('.', '/') + "$$HiddenProxy";
  }

  private static void generateMethod(ClassWriter writer, String interfaceName, Type delegate, String proxyName, String name, String descriptor) {
    var mv = writer.visitMethod(ACC_PUBLIC, name, descriptor, null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);

    if (delegate != null) {
      mv.visitVarInsn(ALOAD, 0);
      mv.visitFieldInsn(GETFIELD, proxyName, "delegate", delegate.getDescriptor());
    }

    var slot = 1;
    var parameterTypes = Type.getArgumentTypes(descriptor);
    for (var parameterType : parameterTypes) {
      mv.visitVarInsn(parameterType.getOpcode(ILOAD), slot);
      slot += parameterType.getSize();
    }

    mv.visitInvokeDynamicInsn(name,
        "(L" + HIDDEN_PROXY_NAME + ';' + (delegate == null? "": delegate.getDescriptor()) + descriptor.substring(1),
        BSM, Type.getType("(L" + interfaceName + ";" + descriptor.substring(1)));

    mv.visitInsn(Type.getReturnType(descriptor).getOpcode(IRETURN));
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  static byte[] generateProxyByteArray(Class<?> lookupClass, Class<?> interfaceType, Class<?> delegateClass) {
    var proxyName = proxyName(lookupClass);
    var delegate = delegateClass == void.class? null: Type.getType(delegateClass);
    var interfaceName = interfaceType.getName().replace('.', '/');

    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(V15, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, proxyName, null,
        "java/lang/Object",
        new String[]{interfaceName, HIDDEN_PROXY_NAME});

    if (delegate != null) {
      var fv = writer.visitField(ACC_PRIVATE | ACC_FINAL, "delegate", delegate.getDescriptor(), null, null);
      fv.visitEnd();
    }

    // add an empty constructor
    var init = writer.visitMethod(ACC_PRIVATE, "<init>", delegate == null? "()V": "(" + delegate.getDescriptor() + ")V", null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    if (delegate != null) {
      init.visitVarInsn(ALOAD, 0);
      init.visitVarInsn(delegate.getOpcode(ILOAD), 1);
      init.visitFieldInsn(PUTFIELD, proxyName, "delegate", delegate.getDescriptor());
    }
    init.visitInsn(RETURN);
    init.visitMaxs(0, 0);
    init.visitEnd();

    // add Object methods
    generateMethod(writer, interfaceName, delegate, proxyName, "equals", "(Ljava/lang/Object;)Z");
    generateMethod(writer, interfaceName, delegate, proxyName, "hashCode", "()I");
    generateMethod(writer, interfaceName, delegate, proxyName, "toString", "()Ljava/lang/String;");

    for(var method: interfaceType.getMethods()) {
      if (!Modifier.isAbstract(method.getModifiers())) {   // filter out static or default method
        continue;
      }

      var methodName = method.getName();
      var descriptor = methodType(method.getReturnType(), method.getParameterTypes())
          .toMethodDescriptorString();

      if (switch(methodName) {
          case "equals" -> descriptor.equals("(Ljava/lang/Object;)Z");
          case "hashCode" -> descriptor.equals("()I");
          case "toString" -> descriptor.equals("()Ljava/lang/String;");
          default -> false;
        }) {
        continue; // skip Object methods already generated
      }

      generateMethod(writer, interfaceName, delegate, proxyName, methodName, descriptor);
    }

    writer.visitEnd();
    return writer.toByteArray();
  }
}
