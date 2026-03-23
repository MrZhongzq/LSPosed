package org.matrix.vector.impl

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.compat.Api100Bridge
import org.matrix.vector.impl.hooks.VectorCtorInvoker
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.impl.hooks.VectorMethodInvoker
import org.matrix.vector.nativebridge.HookBridge

/**
 * Main framework context implementation. Provides modules with capabilities to hook executables,
 * request invokers, and interact with the system.
 */
class VectorContext(
    private val packageName: String,
    private val applicationInfo: ApplicationInfo,
    private val service: ILSPInjectedModuleService,
) : XposedInterface {

    private val remotePrefs = ConcurrentHashMap<String, SharedPreferences>()

    override fun getFrameworkName(): String = BuildConfig.FRAMEWORK_NAME

    override fun getFrameworkVersion(): String = BuildConfig.VERSION_NAME

    override fun getFrameworkVersionCode(): Long = BuildConfig.VERSION_CODE

    override fun getFrameworkProperties(): Long {
        var props = 0L
        // TODO: Dynamically verify capabilities through `service` if required
        props = props or XposedInterface.PROP_CAP_REMOTE
        props = props or XposedInterface.PROP_CAP_SYSTEM
        return props
    }

    override fun hook(origin: Executable): XposedInterface.HookBuilder {
        return VectorHookBuilder(origin)
    }

    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder {
        val clinit =
            HookBridge.getStaticInitializer(origin)
                ?: throw IllegalArgumentException("Class ${origin.name} has no static initializer")
        return VectorHookBuilder(clinit)
    }

    override fun deoptimize(executable: Executable): Boolean {
        return HookBridge.deoptimizeMethod(executable)
    }

    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> {
        return VectorMethodInvoker(method)
    }

    override fun <T : Any> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> {
        return VectorCtorInvoker(constructor)
    }

    override fun getModuleApplicationInfo(): ApplicationInfo = applicationInfo

    override fun getRemotePreferences(name: String): SharedPreferences {
        return remotePrefs.getOrPut(name) { VectorRemotePreferences(service, name) }
    }

    override fun listRemoteFiles(): Array<String> {
        return service.remoteFileList
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        return service.openRemoteFile(name)
            ?: throw FileNotFoundException("Cannot open remote file: $name")
    }

    override fun log(priority: Int, tag: String?, msg: String) {
        log(priority, tag, msg, null)
    }

    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
        val finalTag = tag ?: "VectorContext"
        val prefix = if (packageName.isNotEmpty()) "$packageName: " else ""
        val fullMsg = buildString {
            append(prefix).append(msg)
            if (tr != null) {
                append("\n").append(android.util.Log.getStackTraceString(tr))
            }
        }
        Log.println(priority, finalTag, fullMsg)
    }

    // ==================== API 100 Method Overrides ====================

    override fun getFrameworkPrivilege(): Int = XposedInterface.FRAMEWORK_PRIVILEGE_EMBEDDED

    override fun hook(origin: Method, hooker: Class<out XposedInterface.Hooker>): XposedInterface.MethodUnhooker<Method> {
        return Api100Bridge.doHook(origin, XposedInterface.PRIORITY_DEFAULT, hooker)
    }

    override fun hook(origin: Method, priority: Int, hooker: Class<out XposedInterface.Hooker>): XposedInterface.MethodUnhooker<Method> {
        return Api100Bridge.doHook(origin, priority, hooker)
    }

    override fun <T : Any> hook(origin: Constructor<T>, hooker: Class<out XposedInterface.Hooker>): XposedInterface.MethodUnhooker<Constructor<T>> {
        return Api100Bridge.doHook(origin, XposedInterface.PRIORITY_DEFAULT, hooker)
    }

    override fun <T : Any> hook(origin: Constructor<T>, priority: Int, hooker: Class<out XposedInterface.Hooker>): XposedInterface.MethodUnhooker<Constructor<T>> {
        return Api100Bridge.doHook(origin, priority, hooker)
    }

    override fun <T : Any> hookClassInitializer(origin: Class<T>, hooker: Class<out XposedInterface.Hooker>): XposedInterface.MethodUnhooker<Constructor<T>> {
        val clinit = HookBridge.getStaticInitializer(origin)
            ?: throw IllegalArgumentException("Class ${origin.name} has no static initializer")
        @Suppress("UNCHECKED_CAST")
        return Api100Bridge.doHook(clinit, XposedInterface.PRIORITY_DEFAULT, hooker) as XposedInterface.MethodUnhooker<Constructor<T>>
    }

    override fun <T : Any> hookClassInitializer(origin: Class<T>, priority: Int, hooker: Class<out XposedInterface.Hooker>): XposedInterface.MethodUnhooker<Constructor<T>> {
        val clinit = HookBridge.getStaticInitializer(origin)
            ?: throw IllegalArgumentException("Class ${origin.name} has no static initializer")
        @Suppress("UNCHECKED_CAST")
        return Api100Bridge.doHook(clinit, priority, hooker) as XposedInterface.MethodUnhooker<Constructor<T>>
    }

    override fun invokeOrigin(method: Method, thisObject: Any?, vararg args: Any?): Any? {
        return HookBridge.invokeOriginalMethod(method, thisObject, *args)
    }

    override fun <T : Any> invokeOrigin(constructor: Constructor<T>, thisObject: T, vararg args: Any?) {
        HookBridge.invokeOriginalMethod(constructor, thisObject, *args)
    }

    override fun invokeSpecial(method: Method, thisObject: Any, vararg args: Any?): Any? {
        val shorty = org.matrix.vector.impl.hooks.generateShorty(method)
        return HookBridge.invokeSpecialMethod(method, shorty, method.declaringClass, thisObject, *args)
    }

    override fun <T : Any> invokeSpecial(constructor: Constructor<T>, thisObject: T, vararg args: Any?) {
        val shorty = org.matrix.vector.impl.hooks.generateShorty(constructor)
        HookBridge.invokeSpecialMethod(constructor, shorty, constructor.declaringClass, thisObject, *args)
    }

    override fun <T : Any> newInstanceOrigin(constructor: Constructor<T>, vararg args: Any?): T {
        val instance = HookBridge.allocateObject(constructor.declaringClass)
        HookBridge.invokeOriginalMethod(constructor, instance, *args)
        return instance
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any, U : Any> newInstanceSpecial(constructor: Constructor<T>, subClass: Class<U>, vararg args: Any?): U {
        val instance = HookBridge.allocateObject(subClass)
        val shorty = org.matrix.vector.impl.hooks.generateShorty(constructor)
        HookBridge.invokeSpecialMethod(constructor, shorty, constructor.declaringClass, instance, *args)
        return instance
    }
}

/** Manages the dispatching of modern lifecycle events to loaded modules. */
object VectorLifecycleManager {

    private const val TAG = "VectorLifecycle"

    val activeModules: MutableSet<XposedModule> = ConcurrentHashMap.newKeySet()

    fun dispatchPackageLoaded(
        packageName: String,
        appInfo: ApplicationInfo,
        isFirst: Boolean,
        defaultClassLoader: ClassLoader,
    ) {
        val param =
            object : PackageLoadedParam {
                override fun getPackageName(): String = packageName

                override fun getApplicationInfo(): ApplicationInfo = appInfo

                override fun isFirstPackage(): Boolean = isFirst

                override fun getDefaultClassLoader(): ClassLoader = defaultClassLoader

                override fun getClassLoader(): ClassLoader = defaultClassLoader
            }

        activeModules.forEach { module ->
            runCatching { module.onPackageLoaded(param) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Error in onPackageLoaded for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }

    fun dispatchPackageReady(
        packageName: String,
        appInfo: ApplicationInfo,
        isFirst: Boolean,
        defaultClassLoader: ClassLoader,
        classLoader: ClassLoader,
        appComponentFactory: Any, // Abstracted for API compatibility
    ) {
        val param =
            object : PackageReadyParam {
                override fun getPackageName(): String = packageName

                override fun getApplicationInfo(): ApplicationInfo = appInfo

                override fun isFirstPackage(): Boolean = isFirst

                override fun getDefaultClassLoader(): ClassLoader = defaultClassLoader

                override fun getClassLoader(): ClassLoader = classLoader

                @Suppress("NewApi")
                override fun getAppComponentFactory(): android.app.AppComponentFactory {
                    return appComponentFactory as android.app.AppComponentFactory
                }
            }

        activeModules.forEach { module ->
            runCatching { module.onPackageReady(param) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Error in onPackageReady for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }

    fun dispatchSystemServerStarting(classLoader: ClassLoader) {
        val param =
            object : SystemServerStartingParam {
                override fun getClassLoader(): ClassLoader = classLoader
            }

        activeModules.forEach { module ->
            runCatching { module.onSystemServerStarting(param) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Error in onSystemServerStarting for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }
}
