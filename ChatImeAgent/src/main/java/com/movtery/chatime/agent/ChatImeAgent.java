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
import java.util.ArrayDeque;

/**
 * Moves only the real vanilla ChatScreen extraction/render pass while the Android IME is visible.
 * No Minecraft client jar is modified and no jar is placed in the Minecraft mods directory.
 */
public final class ChatImeAgent {
    private static final int MAGIC = 0x5A43494D; // "ZCIM"
    private static volatile MappedByteBuffer state;
    private static volatile long lastMapAttempt;
    private static FileChannel stateChannel;
    private static RandomAccessFile stateFile;
    private static volatile boolean loggedFirstShift;

    private static final ThreadLocal<ArrayDeque<Boolean>> RENDER_TRANSFORMS =
            new ThreadLocal<ArrayDeque<Boolean>>();
    private static final ThreadLocal<PointerContext> POINTER_CONTEXT =
            new ThreadLocal<PointerContext>();

    private ChatImeAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        System.err.println("[ChatImeAgent] installing vanilla ChatScreen IME transform");
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
                final boolean[] transformedAnything = new boolean[] { false };
                ClassReader reader = new ClassReader(classfileBuffer);
                // No extra locals or injected branches: existing stack-map frames remain valid.
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
                                && (name.equals("extractRenderState") || name.equals("render"))
                                && args.length >= 1
                                && args[0].getSort() == Type.OBJECT
                                && isGraphicsType(args[0].getInternalName());
                        if (renderMethod) {
                            transformedAnything[0] = true;
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                                @Override
                                protected void onMethodEnter() {
                                    loadThis();
                                    loadArg(0);
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method(
                                                    "beginRender", "(Ljava/lang/Object;Ljava/lang/Object;)V"));

                                    // The GUI is translated upward, so hover/selection coordinates
                                    // must be translated inversely in the same actual vanilla screen.
                                    if (args.length >= 3 && args[2].getSort() == Type.INT) {
                                        loadThis();
                                        loadArg(2);
                                        invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                                new org.objectweb.asm.commons.Method(
                                                        "adjustRenderMouseY", "(Ljava/lang/Object;I)I"));
                                        storeArg(2);
                                    }
                                }

                                @Override
                                protected void onMethodExit(int opcode) {
                                    loadThis();
                                    loadArg(0);
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method(
                                                    "endRender", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                                }
                            };
                        }

                        boolean eventCoordinates = hasMouseButtonEvent(args);
                        boolean numericCoordinates = hasTwoLeadingDoubles(args);
                        if ((chatScreen || baseScreen) && isPointerMethod(name)
                                && (eventCoordinates || numericCoordinates)) {
                            transformedAnything[0] = true;
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                                @Override
                                protected void onMethodEnter() {
                                    loadThis();
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method(
                                                    "beginPointerDispatch", "(Ljava/lang/Object;)V"));

                                    if (eventCoordinates && usesMouseButtonEvent(name)) {
                                        loadThis();
                                        loadArg(0);
                                        invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                                new org.objectweb.asm.commons.Method(
                                                        "shiftMouseEvent",
                                                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                                        checkCast(args[0]);
                                        storeArg(0);
                                    } else if (numericCoordinates) {
                                        loadThis();
                                        loadArg(1);
                                        invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                                new org.objectweb.asm.commons.Method(
                                                        "shiftPointerY", "(Ljava/lang/Object;D)D"));
                                        storeArg(1);
                                    }
                                }

                                @Override
                                protected void onMethodExit(int opcode) {
                                    loadThis();
                                    invokeStatic(Type.getObjectType("com/movtery/chatime/agent/ChatImeAgent"),
                                            new org.objectweb.asm.commons.Method(
                                                    "endPointerDispatch", "(Ljava/lang/Object;)V"));
                                }
                            };
                        }
                        return delegate;
                    }
                };
                reader.accept(visitor, 0);
                if (transformedAnything[0]) {
                    System.err.println("[ChatImeAgent] transformed " + className);
                    return writer.toByteArray();
                }
                return null;
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
        // MC 26.3 uses GuiGraphicsExtractor + Matrix3x2fStack; older versions use GuiGraphics/PoseStack.
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

    public static void beginRender(Object screen, Object graphics) {
        ArrayDeque<Boolean> stack = RENDER_TRANSFORMS.get();
        if (stack == null) {
            stack = new ArrayDeque<Boolean>();
            RENDER_TRANSFORMS.set(stack);
        }

        boolean transformed = false;
        boolean pushed = false;
        try {
            float offset = getOffset(screen);
            if (offset > 0f && graphics != null) {
                Object pose = findPose(graphics);
                if (pose != null) {
                    invokeNoArgs(pose, "pushPose", "pushMatrix", "push");
                    pushed = true;
                    translate(pose, -offset);
                    transformed = true;
                    if (!loggedFirstShift) {
                        loggedFirstShift = true;
                        System.err.println("[ChatImeAgent] vanilla chat shifted by " + offset + " GUI pixels");
                    }
                }
            }
        } catch (Throwable error) {
            if (pushed) {
                try {
                    Object pose = findPose(graphics);
                    if (pose != null) invokeNoArgs(pose, "popPose", "popMatrix", "pop");
                } catch (Throwable ignored) { }
            }
            System.err.println("[ChatImeAgent] beginRender failed: " + error);
        }
        stack.push(Boolean.valueOf(transformed));
    }

    public static void endRender(Object screen, Object graphics) {
        ArrayDeque<Boolean> stack = RENDER_TRANSFORMS.get();
        if (stack == null || stack.isEmpty()) return;
        boolean transformed = stack.pop().booleanValue();
        if (stack.isEmpty()) RENDER_TRANSFORMS.remove();
        if (!transformed) return;
        try {
            Object pose = findPose(graphics);
            if (pose != null) invokeNoArgs(pose, "popPose", "popMatrix", "pop");
        } catch (Throwable error) {
            System.err.println("[ChatImeAgent] endRender failed: " + error);
        }
    }

    public static int adjustRenderMouseY(Object screen, int originalY) {
        float offset = getOffset(screen);
        return offset > 0f ? originalY + Math.round(offset) : originalY;
    }

    private static final class PointerContext {
        int depth;
        boolean shifted;
    }

    public static void beginPointerDispatch(Object screen) {
        if (!isChatScreenInstance(screen)) return;
        PointerContext context = POINTER_CONTEXT.get();
        if (context == null) {
            context = new PointerContext();
            POINTER_CONTEXT.set(context);
        }
        if (context.depth == 0) context.shifted = false;
        context.depth++;
    }

    public static void endPointerDispatch(Object screen) {
        PointerContext context = POINTER_CONTEXT.get();
        if (context == null) return;
        context.depth--;
        if (context.depth <= 0) POINTER_CONTEXT.remove();
    }

    /** MC 26.3 uses immutable MouseButtonEvent values; offset its actual vanilla event. */
    public static Object shiftMouseEvent(Object screen, Object event) {
        PointerContext context = POINTER_CONTEXT.get();
        if (!isChatScreenInstance(screen) || context == null || context.shifted) return event;
        context.shifted = true;
        try {
            float offset = getOffset(screen);
            if (offset <= 0f || event == null
                    || !event.getClass().getSimpleName().equals("MouseButtonEvent")) return event;
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

    public static double shiftPointerY(Object screen, double originalY) {
        PointerContext context = POINTER_CONTEXT.get();
        if (!isChatScreenInstance(screen) || context == null || context.shifted) return originalY;
        context.shifted = true;
        return originalY + getOffset(screen);
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
