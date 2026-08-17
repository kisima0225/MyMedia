package com.mymedia.library;

/**
 * 媒体库的顶层分区。创建后不可变——改变一个库的 domain 等同于
 * 把它的全部内容换成另一种形态，语义上应该是新建一个库。
 */
public enum LibraryDomain { VIDEO, IMAGE }
