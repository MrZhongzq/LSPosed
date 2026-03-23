package org.matrix.vector.impl.compat

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.error.HookFailedError
import org.matrix.vector.impl.hooks.VectorNativeHooker
import org.matrix.vector.nativebridge.HookBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Bridge for API 100 hook registration.
 * Validates hooker classes (finding static before/after methods)
 * and registers them through VectorNativeHooker with HookerCallback objects.
 */
object Api100Bridge {

    private val TAG = "Api100Bridge"

    @JvmStatic
    fun dummyCallback() {
        // Placeholder for missing before/after method
    }

    private val dummyMethod: Method by lazy {
        Api100Bridge::class.java.getMethod("dummyCallback")
    }

    /**
     * Register an API 100 style hook with before/after static methods.
     */
    @JvmStatic
    fun <T : Executable> doHook(
        hookMethod: T,
        priority: Int,
        hooker: Class<out XposedInterface.Hooker>,
    ): XposedInterface.MethodUnhooker<T> {
        // Validation
        if (Modifier.isAbstract(hookMethod.modifiers)) {
            throw IllegalArgumentException("Cannot hook abstract methods: $hookMethod")
        }
        if (hookMethod.declaringClass == Method::class.java && hookMethod.name == "invoke") {
            throw IllegalArgumentException("Cannot hook Method.invoke")
        }

        // Find before/after static methods
        var beforeInvocation: Method? = null
        var afterInvocation: Method? = null
        val requiredModifiers = Modifier.PUBLIC or Modifier.STATIC

        for (method in hooker.declaredMethods) {
            when (method.name) {
                "before" -> {
                    if (beforeInvocation != null) {
                        throw IllegalArgumentException("More than one method named before")
                    }
                    var valid = (method.modifiers and requiredModifiers) == requiredModifiers
                    val params = method.parameterTypes
                    if (params.size == 1) {
                        valid = valid and (params[0] == BeforeHookCallback::class.java)
                    } else if (params.isNotEmpty()) {
                        valid = false
                    }
                    if (!valid) {
                        throw IllegalArgumentException("before method format is invalid")
                    }
                    beforeInvocation = method
                }
                "after" -> {
                    if (afterInvocation != null) {
                        throw IllegalArgumentException("More than one method named after")
                    }
                    var valid = (method.modifiers and requiredModifiers) == requiredModifiers
                    valid = valid and (method.returnType == Void.TYPE)
                    val params = method.parameterTypes
                    if (params.size == 1 || params.size == 2) {
                        valid = valid and (params[0] == AfterHookCallback::class.java)
                    } else if (params.isNotEmpty()) {
                        valid = false
                    }
                    if (!valid) {
                        throw IllegalArgumentException("after method format is invalid")
                    }
                    afterInvocation = method
                }
            }
        }

        if (beforeInvocation == null && afterInvocation == null) {
            throw IllegalArgumentException("No method named before or after found in ${hooker.name}")
        }

        // Fill in dummy for missing method
        val before = beforeInvocation ?: dummyMethod
        val after = afterInvocation ?: dummyMethod

        // Validate context passing between before and after
        if (beforeInvocation != null && afterInvocation != null) {
            val ret = before.returnType
            val params = after.parameterTypes
            if (ret != Void.TYPE && params.size == 2 && ret != params[1]) {
                throw IllegalArgumentException("before and after method format is invalid")
            }
        }

        val callback = Api100HookerCallback(before, after)

        // Register through VectorNativeHooker (not a separate hooker class)
        if (HookBridge.hookMethod(true, hookMethod, VectorNativeHooker::class.java, priority, callback)) {
            return object : XposedInterface.MethodUnhooker<T> {
                override fun getOrigin(): T = hookMethod
                override fun unhook() {
                    HookBridge.unhookMethod(true, hookMethod, callback)
                }
            }
        }
        throw HookFailedError("Cannot hook $hookMethod")
    }
}
