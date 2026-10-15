package com.movtery.chatime.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.ProtectionDomain;

/**
 * Runtime-only renderer patch. It modifies the vanilla ChatScreen class in memory;
 * no Minecraft client jar or mods directory is changed.
 */
public final class ChatImeAgent {
    private static final int MAGIC = 0x5A43494D; // "ZCIM"
    private static volatile MappedByteBuffer state;
    private static volatile long lastMapAttempt;
    private static final ThreadLocal<Boolean> POINTER_DISPATCH = new ThreadLocal<Boolean>();
    private static FileChannel stateChannel;
    private static RandomAccessFile stateFile;

    private ChatImeAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        System.err.println("[ChatImeAgent] installing vanilla chat IME transformer");
        instrumentation.addTransformer(new Transformer(), false);
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null || classfileBuffer == null) return null;
            final boolean chatScreen = isChatScreenName(className);
            final boolean baseScreen = isBaseScreenName(className);
            if (!chatScreen && !baseScreen) return null;

            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (delegate == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                            return delegate;
                        }

                        Type[] args = Type.getArgumentTypes(descriptor);
                        boolean renderMethod = chatScreen
                                && (name.equals("render") || name.equals("extractRenderState"))
                                && args.length >= 1
                                && args[0].getSort() == Type.OBJECT
                                && isGraphicsType(args[0].getInternalName());
                        if (renderMethod) {
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                                private int transformedLocal = -1;

                                @Override
                                protected void onMethodEnter() {
                                    loadThis();
                                    loadArg(0);
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method("begin", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
                                    transformedLocal = newLocal(Type.BOOLEAN_TYPE);
                                    storeLocal(transformedLocal);
                                }

                                @Override
                                protected void onMethodExit(int opcode) {
                                    if (transformedLocal < 0) return;
                                    loadThis();
                                    loadArg(0);
                                    loadLocal(transformedLocal);
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method("end", "(Ljava/lang/Object;Ljava/lang/Object;Z)V"));
                                }
                            };
                        }

                        boolean eventCoordinates = hasMouseButtonEvent(args);
                        boolean numericCoordinates = hasTwoLeadingDoubles(args);
                        if ((chatScreen || baseScreen) && isPointerMethod(name)
                                && (eventCoordinates || numericCoordinates)) {
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                                private int ownsDispatchLocal = -1;

                                @Override
                                protected void onMethodEnter() {
                                    loadThis();
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method("beginPointerDispatch", "(Ljava/lang/Object;)Z"));
                                    ownsDispatchLocal = newLocal(Type.BOOLEAN_TYPE);
                                    storeLocal(ownsDispatchLocal);

                                    Label unchanged = new Label();
                                    loadLocal(ownsDispatchLocal);
                                    ifZCmp(EQ, unchanged);
                                    if (eventCoordinates && usesMouseButtonEvent(name)) {
                                        loadThis();
                                        loadArg(0);
                                        invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                                new org.objectweb.asm.commons.Method("shiftMouseEvent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                                        checkCast(args[0]);
                                        storeArg(0);
                                    } else if (numericCoordinates) {
                                        loadThis();
                                        loadArg(1);
                                        invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                                new org.objectweb.asm.commons.Method("shiftPointerY", "(Ljava/lang/Object;D)D"));
                                        storeArg(1);
                                    }
                                    mark(unchanged);
                                }

                                @Override
                                protected void onMethodExit(int opcode) {
                                    if (ownsDispatchLocal < 0) return;
                                    loadThis();
                                    loadLocal(ownsDispatchLocal);
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method("endPointerDispatch", "(Ljava/lang/Object;Z)V"));
                                }
                            };
                        }
                        return delegate;
                    }
                };
                reader.accept(visitor, 0);
                return writer.toByteArray();
            } catch (Throwable error) {
                System.err.println("[ChatImeAgent] could not transform " + className + ": " + error);
                return null;
            }
        }
    }

    private static boolean isChatScreenName(String name) {
        return name.endsWith("/ChatScreen") || name.endsWith("/GuiChat");
    }

    private static boolean isBaseScreenName(String name) {
        return name.equals("net/minecraft/client/gui/screens/Screen")
                || name.equals("net/minecraft/client/gui/GuiScreen");
    }

    private static boolean isGraphicsType(String name) {
        // Minecraft 26.3 renamed GuiGraphics to GuiGraphicsExtractor.
        return name.endsWith("/GuiGraphics") || name.endsWith("/GuiGraphicsExtractor")
                || name.endsWith("/PoseStack") || name.endsWith("/MatrixStack")
                || name.endsWith("/DrawContext");
    }

    private static boolean isPointerMethod(String name) {
        return name.equals("mouseClicked") || name.equals("mouseReleased")
                || name.equals("mouseDragged") || name.equals("mouseMoved")
                || name.equals("mouseScrolled");
    }

    private static boolean hasTwoLeadingDoubles(Type[] args) {
        return args.length >= 2 && args[0].getSort() == Type.DOUBLE && args[1].getSort() == Type.DOUBLE;
    }

    private static boolean hasMouseButtonEvent(Type[] args) {
        return args.length >= 1 && args[0].getSort() == Type.OBJECT
                && args[0].getInternalName().endsWith("/MouseButtonEvent");
    }

    private static boolean usesMouseButtonEvent(String name) {
        return name.equals("mouseClicked") || name.equals("mouseReleased") || name.equals("mouseDragged");
    }

    /** Prevent double translation when a ChatScreen override delegates to Screen.super. */
    public static boolean beginPointerDispatch(Object screen) {
        if (!isChatScreenInstance(screen) || Boolean.TRUE.equals(POINTER_DISPATCH.get())) return false;
        POINTER_DISPATCH.set(Boolean.TRUE);
        return true;
    }

    public static void endPointerDispatch(Object screen, boolean ownsDispatch) {
        if (ownsDispatch) POINTER_DISPATCH.remove();
    }

    /** Minecraft 26.3 passes immutable MouseButtonEvent records rather than raw x/y/button args. */
    public static Object shiftMouseEvent(Object screen, Object event) {
        try {
            if (event == null || !isChatScreenInstance(screen)) return event;
            float offset = getOffset(screen);
            if (offset <= 0f || !event.getClass().getSimpleName().equals("MouseButtonEvent")) return event;
            Object xValue = invokeNoArgs(event, "x");
            Object yValue = invokeNoArgs(event, "y");
            Object buttonInfo = invokeNoArgs(event, "buttonInfo");
            if (!(xValue instanceof Number) || !(yValue instanceof Number)) return event;
            double x = ((Number) xValue).doubleValue();
            double y = ((Number) yValue).doubleValue() + offset;
            for (Constructor<?> constructor : event.getClass().getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length != 3 || types[0] != double.class || types[1] != double.class
                        || (buttonInfo != null && !types[2].isInstance(buttonInfo))) continue;
                constructor.setAccessible(true);
                return constructor.newInstance(x, y, buttonInfo);
            }
        } catch (Throwable error) {
            System.err.println("[ChatImeAgent] could not translate MouseButtonEvent: " + error);
        }
        return event;
    }

    /** Push the existing vanilla graphics matrix so all native ChatScreen widgets move together. */
    public static boolean begin(Object screen, Object graphics) {
        try {
            float offset = getOffset(screen);
            if (offset <= 0f || graphics == null) return false;
            Object pose = findPose(graphics);
            if (pose == null) return false;
            invokeNoArgs(pose, "pushPose", "pushMatrix", "push");
            translate(pose, -offset);
            return true;
        } catch (Throwable error) {
            System.err.println("[ChatImeAgent] begin failed: " + error);
            return false;
        }
    }

    public static void end(Object screen, Object graphics, boolean transformed) {
        if (!transformed) return;
        try {
            Object pose = findPose(graphics);
            if (pose != null) invokeNoArgs(pose, "popPose", "popMatrix", "pop");
        } catch (Throwable error) {
            System.err.println("[ChatImeAgent] end failed: " + error);
        }
    }

    /** Screen pointer coordinates are translated inversely to keep native widget hit targets aligned. */
    public static double shiftPointerY(Object screen, double originalY) {
        try {
            if (!isChatScreenInstance(screen)) return originalY;
            return originalY + getOffset(screen);
        } catch (Throwable ignored) {
            return originalY;
        }
    }

    private static boolean isChatScreenInstance(Object value) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if ("ChatScreen".equals(name) || "GuiChat".equals(name)) return true;
        }
        return false;
    }

    private static float getOffset(Object screen) {
        if (screen == null || !isChatScreenInstance(screen)) return 0f;
        MappedByteBuffer data = getState();
        if (data == null) return 0f;
        try {
            if (data.getInt(0) != MAGIC) return 0f;
            int insetPx = Math.max(0, data.getInt(4));
            int physicalHeight = Math.max(1, data.getInt(8));
            int guiHeight = readScreenHeight(screen);
            if (insetPx == 0 || guiHeight <= 0) return 0f;
            return ((float) insetPx * (float) guiHeight) / (float) physicalHeight;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static int readScreenHeight(Object screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("height");
                field.setAccessible(true);
                Object value = field.get(screen);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Throwable ignored) { }
        }
        for (String name : new String[]{"guiHeight", "height"}) {
            try {
                Object result = invokeNoArgs(screen, name);
                if (result instanceof Number) return ((Number) result).intValue();
            } catch (Throwable ignored) { }
        }
        return 0;
    }

    private static MappedByteBuffer getState() {
        MappedByteBuffer existing = state;
        if (existing != null) return existing;
        long now = System.currentTimeMillis();
        if (now - lastMapAttempt < 1000L) return null;
        synchronized (ChatImeAgent.class) {
            if (state != null) return state;
            if (now - lastMapAttempt < 1000L) return null;
            lastMapAttempt = now;
            String path = System.getProperty("zalith.chatime.state", "");
            if (path.isEmpty()) return null;
            try {
                File file = new File(path);
                if (!file.isFile() || file.length() < 12L) return null;
                stateFile = new RandomAccessFile(file, "r");
                stateChannel = stateFile.getChannel();
                state = stateChannel.map(FileChannel.MapMode.READ_ONLY, 0, 12);
                return state;
            } catch (Throwable error) {
                System.err.println("[ChatImeAgent] state map unavailable: " + error);
                return null;
            }
        }
    }

    private static Object findPose(Object graphics) throws Exception {
        if (graphics == null) return null;
        String simple = graphics.getClass().getSimpleName();
        if ("PoseStack".equals(simple) || "MatrixStack".equals(simple)
                || "Matrix3x2fStack".equals(simple)) return graphics;
        for (String name : new String[]{"pose", "poseStack", "getPose", "getPoseStack", "matrices"}) {
            try {
                Object result = invokeNoArgs(graphics, name);
                if (result != null) return result;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static void translate(Object pose, float y) throws Exception {
        for (Method method : allMethods(pose.getClass())) {
            if (!method.getName().equals("translate") && !method.getName().equals("translateLocal")) continue;
            Class<?>[] types = method.getParameterTypes();
            method.setAccessible(true);
            if (types.length == 3 && types[0] == double.class && types[1] == double.class && types[2] == double.class) {
                method.invoke(pose, 0.0d, (double) y, 0.0d);
                return;
            }
            if (types.length == 3 && types[0] == float.class && types[1] == float.class && types[2] == float.class) {
                method.invoke(pose, 0.0f, y, 0.0f);
                return;
            }
            // The Minecraft 26.3 Matrix3x2fStack is two-dimensional.
            if (types.length == 2 && types[0] == float.class && types[1] == float.class) {
                method.invoke(pose, 0.0f, y);
                return;
            }
            if (types.length == 2 && types[0] == double.class && types[1] == double.class) {
                method.invoke(pose, 0.0d, (double) y);
                return;
            }
        }
        throw new NoSuchMethodException("PoseStack/Matrix3x2fStack translate");
    }

    private static Object invokeNoArgs(Object target, String... names) throws Exception {
        for (String name : names) {
            for (Method method : allMethods(target.getClass())) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName());
    }

    private static Method[] allMethods(Class<?> type) {
        java.util.ArrayList<Method> methods = new java.util.ArrayList<Method>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                for (Method method : current.getDeclaredMethods()) methods.add(method);
            } catch (Throwable ignored) { }
        }
        return methods.toArray(new Method[0]);
    }
}
