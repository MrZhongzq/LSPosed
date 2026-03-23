package org.matrix.vector.impl.compat

import java.lang.reflect.Method

/**
 * Stores the before/after method references for an API 100 hook.
 * This object is stored as an opaque jobject in the native modern_callbacks multimap.
 */
class Api100HookerCallback(
    @JvmField val beforeInvocation: Method,
    @JvmField val afterInvocation: Method,
    @JvmField val beforeParams: Int = beforeInvocation.parameterCount,
    @JvmField val afterParams: Int = afterInvocation.parameterCount,
)
