package com.github.forax.hiddenproxy;

import java.io.IOException;
import java.lang.constant.MethodTypeDesc;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;
import static org.objectweb.asm.Opcodes.*;

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

  static byte[] generateProxyByteArray(Class<?> lookupClass, Class<?> interfaceType, Class<?> delegateClass)
      throws IOException {
    var proxyName = proxyName(lookupClass);
    var delegate = delegateClass == void.class? null: Type.getType(delegateClass);
    var interfaceName = interfaceType.getName().replace('.', '/');
    var resourceName = "/" + interfaceName + ".class";
    var source = interfaceType.getResourceAsStream(resourceName);
    var reader = new ClassReader(source);
    var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassVisitor(ASM8, writer) {
      @Override
      public void visit(int version, int access, String name, String signature, String superName,
          String[] interfaces) {
        super.visit(V15, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, proxyName, signature,
            "java/lang/Object",
            new String[]{interfaceName, HIDDEN_PROXY_NAME});
      }

      @Override
      public void visitSource(String source, String debug) {
        // empty
      }

      @Override
      public void visitInnerClass(String name, String outerName, String innerName, int access) {
        // empty
      }

      @Override
      public void visitOuterClass(String owner, String name, String descriptor) {
        // empty
      }

      @Override
      public void visitNestHost(String nestHost) {
        // empty
      }

      @Override
      public void visitNestMember(String nestMember) {
        // empty
      }

      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
          String descriptor, boolean visible) {
        return null;
      }

      @Override
      public void visitAttribute(Attribute attribute) {
        // empty
      }

      @Override
      public FieldVisitor visitField(int access, String name, String descriptor, String signature,
          Object value) {
        return null;
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
          String[] exceptions) {
        if ((access & ACC_ABSTRACT) == 0) { // static or default method
          return null;
        }
        var mv = super.visitMethod(ACC_PUBLIC, name, descriptor, null, exceptions);
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
        return null;
      }

      @Override
      public void visitEnd() {
        // add delegate
        if (delegate != null) {
          var fv = super.visitField(ACC_PRIVATE | ACC_FINAL, "delegate", delegate.getDescriptor(), null, null);
          fv.visitEnd();
        }

        // add an empty constructor
        var init = super.visitMethod(ACC_PRIVATE, "<init>", delegate == null? "()V": "(" + delegate.getDescriptor() + ")V", null, null);
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
        visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "equals", "(Ljava/lang/Object;)Z", null, null);
        visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "hashCode", "()I", null, null);
        visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "toString", "()Ljava/lang/String;", null, null);
        super.visitEnd();
      }
    }, ClassReader.SKIP_CODE);

    return writer.toByteArray();
  }
}
