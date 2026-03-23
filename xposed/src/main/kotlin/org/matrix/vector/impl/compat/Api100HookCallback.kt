package org.matrix.vector.impl.compat

import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import java.lang.reflect.Executable
import java.lang.reflect.Member

/**
 * Mutable callback state for API 100 before/after hook dispatch.
 * Implements both BeforeHookCallback and AfterHookCallback.
 */
class Api100HookCallback(
    private val executable: Executable,
    private val _thisObject: Any?,
    private val _args: Array<Any?>,
) : BeforeHookCallback, AfterHookCallback {

    @JvmField var result: Any? = null
    @JvmField var throwable: Throwable? = null
    @JvmField var isSkipped: Boolean = false

    // Shared
    override fun getMember(): Member = executable
    override fun getThisObject(): Any? = _thisObject
    override fun getArgs(): Array<Any?> = _args

    // Before
    override fun returnAndSkip(result: Any?) {
        this.result = result
        this.throwable = null
        this.isSkipped = true
    }

    override fun throwAndSkip(throwable: Throwable?) {
        this.result = null
        this.throwable = throwable
        this.isSkipped = true
    }

    // After
    override fun getResult(): Any? = result
    override fun getThrowable(): Throwable? = throwable
    override fun isSkipped(): Boolean = isSkipped

    override fun setResult(result: Any?) {
        this.result = result
        this.throwable = null
    }

    override fun setThrowable(throwable: Throwable?) {
        this.result = null
        this.throwable = throwable
    }
}
