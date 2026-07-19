package com.photographer.focusstack.cr3.bmff;

/**
 * A single CTBO record (Canon Track Byte Order / offset table entry).
 *
 * @param index  record index (1-based)
 * @param offset absolute byte offset in the file
 * @param size   size in bytes
 */
public record CtboEntry(int index, long offset, long size) {}
