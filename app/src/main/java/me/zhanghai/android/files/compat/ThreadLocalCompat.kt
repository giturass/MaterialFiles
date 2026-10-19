package me.zhanghai.android.files.compat

import kotlin.reflect.KClass

fun <T> KClass<ThreadLocal<*>>.withInitial(supplier: () -> T): ThreadLocal<T> =
    ThreadLocal.withInitial(supplier)
