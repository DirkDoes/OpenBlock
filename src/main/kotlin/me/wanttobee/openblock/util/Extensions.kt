package me.wanttobee.openblock.util

fun <T> List<T>.middleOrNull(): T? {
	if (isEmpty()) {
		return null
	}
	return this[size / 2]
}
