package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.compat.Api100HookCallback
import org.matrix.vector.impl.compat.Api100HookerCallback
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/** Builder for configuring and registering hooks. */
class VectorHookBuilder(private val origin: Executable) : HookBuilder {

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun intercept(hooker: Hooker): HookHandle {
        if (Modifier.isAbstract(origin.modifiers)) {
            throw IllegalArgumentException("Cannot hook abstract methods: $origin")
        } else if (origin.declaringClass.classLoader == VectorHookBuilder::class.java.classLoader) {
            throw IllegalArgumentException("Do not allow hooking inner methods")
        } else if (
            origin is Method &&
                origin.declaringClass == Method::class.java &&
                origin.name == "invoke"
        ) {
            throw IllegalArgumentException("Cannot hook Method.invoke")
        }

        val record = VectorHookRecord(hooker, priority, exceptionMode)

        // Register natively. HookBridge now stores VectorHookRecord instead of HookerCallback.
        if (
            !HookBridge.hookMethod(true, origin, VectorNativeHooker::class.java, priority, record)
        ) {
            throw HookFailedError("Cannot hook $origin")
        }

        return object : HookHandle {
            override fun getExecutable(): Executable = origin

            override fun unhook() {
                HookBridge.unhookMethod(true, origin, record)
            }
        }
    }
}

/**
 * The native callback entrypoint. Instantiated natively by [HookBridge] when a hooked method is
 * hit.
 */
class VectorNativeHooker<T : Executable>(private val method: T) {

    private val isStatic = Modifier.isStatic(method.modifiers)
    private val returnType = if (method is Method) method.returnType else null

    /** Invoked by C++ via JNI. */
    fun callback(args: Array<Any?>): Any? {
        val thisObject = if (isStatic) null else args[0]
        val actualArgs = if (isStatic) args else args.sliceArray(1 until args.size)

        // Retrieve the hook snapshots. Use Any::class.java for mixed callback types.
        val snapshots = HookBridge.callbackSnapshot(Any::class.java, method)

        val rawModernHooks = snapshots[0]
        val legacyHooks = snapshots[1]

        // Fast path: No hooks active
        if (rawModernHooks.isEmpty() && legacyHooks.isEmpty()) {
            return invokeOriginalSafely(thisObject, actualArgs)
        }

        // Separate and wrap API 100 callbacks into the unified interceptor chain.
        // API 101 hooks are VectorHookRecord, API 100 hooks are Api100HookerCallback.
        // Both are sorted by priority in the native multimap already.
        val modernHooks = rawModernHooks.map { hook ->
            when (hook) {
                is VectorHookRecord -> hook
                is Api100HookerCallback -> wrapApi100AsInterceptor(hook)
                else -> {
                    Utils.logW("Unknown modern hook type: ${hook?.javaClass?.name}")
                    VectorHookRecord(object : Hooker { override fun intercept(chain: XposedInterface.Chain) = chain.proceed() }, XposedInterface.PRIORITY_DEFAULT, ExceptionMode.DEFAULT)
                }
            }
        }.toTypedArray()

        val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
            val delegate = VectorBootstrap.delegate
            if (legacyHooks.isNotEmpty() && delegate != null) {
                delegate.processLegacyHook(method, tObj, tArgs, legacyHooks) {
                    invokeOriginalSafely(tObj, tArgs)
                }
            } else {
                invokeOriginalSafely(tObj, tArgs)
            }
        }

        val rootChain = VectorChain(method, thisObject, actualArgs, modernHooks, 0, terminal)

        val result = rootChain.proceed()

        // Type safety validation before returning to C++
        if (returnType != null && returnType != Void.TYPE) {
            if (result == null) {
                if (returnType.isPrimitive) {
                    throw NullPointerException(
                        "Hook returned null for a primitive return type: $method"
                    )
                }
            } else {
                // Use the JNI bridge for the most reliable type check across ClassLoaders
                if (
                    !HookBridge.instanceOf(result, returnType) &&
                        !isBoxingCompatible(result, returnType)
                ) {
                    Utils.logD(
                        "Hook return type mismatch. Expected ${returnType.name}, got ${result.javaClass.name}"
                    )
                }
            }
        }

        return result
    }

    /** Handles primitive boxing compatibility (e.g., Integer object vs int primitive). */
    private fun isBoxingCompatible(obj: Any, targetType: Class<*>): Boolean {
        if (!targetType.isPrimitive) return false
        return when (targetType) {
            Int::class.javaPrimitiveType -> obj is Int
            Long::class.javaPrimitiveType -> obj is Long
            Boolean::class.javaPrimitiveType -> obj is Boolean
            Double::class.javaPrimitiveType -> obj is Double
            Float::class.javaPrimitiveType -> obj is Float
            Byte::class.javaPrimitiveType -> obj is Byte
            Char::class.javaPrimitiveType -> obj is Char
            Short::class.javaPrimitiveType -> obj is Short
            else -> false
        }
    }

    /** Safely invokes the original method, unwrapping InvocationTargetExceptions. */
    private fun invokeOriginalSafely(tObj: Any?, tArgs: Array<Any?>): Any? {
        return try {
            HookBridge.invokeOriginalMethod(method, tObj, *tArgs)
        } catch (ite: InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }

    /**
     * Wraps an API 100 HookerCallback as a VectorHookRecord that participates
     * in the interceptor chain. This preserves priority ordering across APIs.
     */
    private fun wrapApi100AsInterceptor(hookerCallback: Api100HookerCallback): VectorHookRecord {
        val hooker = object : Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val ctx = Api100HookCallback(
                    chain.executable,
                    chain.thisObject,
                    chain.args.toTypedArray()
                )

                // Call before
                var beforeContext: Any? = null
                try {
                    if (hookerCallback.beforeParams == 0) {
                        beforeContext = hookerCallback.beforeInvocation.invoke(null)
                    } else {
                        beforeContext = hookerCallback.beforeInvocation.invoke(null, ctx)
                    }
                } catch (t: Throwable) {
                    Utils.logE("API 100 before hook error", t)
                    ctx.setResult(null)
                    ctx.isSkipped = false
                }

                if (ctx.isSkipped) {
                    val t = ctx.throwable
                    if (t != null) throw t
                    return ctx.result
                }

                // Proceed down the chain
                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    ctx.setThrowable(t)
                    null
                }
                if (ctx.throwable == null) {
                    ctx.setResult(result)
                }

                // Call after
                try {
                    when (hookerCallback.afterParams) {
                        0 -> hookerCallback.afterInvocation.invoke(null)
                        1 -> hookerCallback.afterInvocation.invoke(null, ctx)
                        else -> hookerCallback.afterInvocation.invoke(null, ctx, beforeContext)
                    }
                } catch (t: Throwable) {
                    Utils.logE("API 100 after hook error", t)
                    if (ctx.throwable == null) {
                        ctx.setResult(result)
                    }
                }

                val throwable = ctx.throwable
                if (throwable != null) throw throwable
                return ctx.result
            }
        }

        return VectorHookRecord(hooker, 0, ExceptionMode.PASSTHROUGH)
    }
}
