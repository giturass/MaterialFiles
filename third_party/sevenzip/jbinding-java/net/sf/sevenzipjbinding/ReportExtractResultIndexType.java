package net.sf.sevenzipjbinding;

/**
 * Index type parameter for
 * {@link IArchiveExtractCallback#reportExtractResult(ReportExtractResultIndexType, int, ExtractOperationResult)}.
 *
 * This enum corresponds to 7-zip's NEventIndexType namespace and indicates what kind of index is being reported in
 * extract result callbacks.
 *
 * @author Boris Brodski
 * @since 23.01-2.2
 */
public enum ReportExtractResultIndexType {
    /**
     * No index available or applicable.
     */
    NO_INDEX,

    /**
     * Index in the input (source) archive. Used when reporting errors for specific files within the archive being
     * extracted.
     */
    IN_ARCHIVE_INDEX,

    /**
     * Block index. Used for multi-volume or compressed block-level operations.
     */
    BLOCK_INDEX,

    /**
     * Index in the output (destination) archive. Less commonly used.
     */
    OUT_ARCHIVE_INDEX;

    /**
     * Convert an integer index to its corresponding enumeration value.
     *
     * @param index
     *            the integer index (typically from 7-zip native code)
     * @return the corresponding ReportExtractResultIndexType, or NO_INDEX if out of range
     */
    public static ReportExtractResultIndexType getIndexType(int index) {
        ReportExtractResultIndexType[] values = ReportExtractResultIndexType.values();
        if (index < 0 || index >= values.length) {
            return NO_INDEX;
        }
        return values[index];
    }
}
