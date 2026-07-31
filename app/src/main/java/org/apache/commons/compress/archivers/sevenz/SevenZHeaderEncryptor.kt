/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package org.apache.commons.compress.archivers.sevenz

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32

/**
 * Encrypts the header that [SevenZOutputFile] leaves in plain text at the end of a finished archive,
 * so that entry names are hidden as well as entry content.
 *
 * Commons Compress can only encrypt content: [SevenZOutputFile] has no API for header encryption,
 * and the code that serialises the header is private. Rather than fork the whole class, this
 * rewrites the tail of an already finished archive:
 *
 *  1. The plain header is read back out of the file and encrypted with AES256SHA256.
 *  2. The ciphertext is written over where the plain header used to be, which turns it into an
 *     ordinary packed stream sitting at the offset the signature header already points at.
 *  3. A small `kEncodedHeader` streams info describing that packed stream is written after it.
 *  4. The 32 byte signature header is rewritten to point at the new header.
 *
 * This class lives in the Commons Compress package because it needs [NID], [AES256Options],
 * [Coders] and [SevenZFile]'s constants, all of which are package private.
 *
 * @see SevenZFile.readEncodedHeader
 */
internal object SevenZHeaderEncryptor {
    /**
     * Rewrites the header of the finished archive in [channel] as an encrypted one.
     *
     * Must be called after [SevenZOutputFile.finish] and before the channel is closed.
     */
    @Throws(IOException::class)
    fun encryptHeader(channel: SeekableByteChannel, password: CharArray) {
        val signatureHeader = channel.readFully(0L, SevenZFile.SIGNATURE_HEADER_SIZE)
        val plainHeaderOffset = signatureHeader.getLong(NEXT_HEADER_OFFSET_POSITION)
        val plainHeaderSize = signatureHeader.getLong(NEXT_HEADER_SIZE_POSITION)
        if (plainHeaderSize <= 0L || plainHeaderSize > MAX_HEADER_SIZE) {
            throw IOException("Unsupported 7z header size $plainHeaderSize")
        }
        val plainHeaderPosition = SevenZFile.SIGNATURE_HEADER_SIZE + plainHeaderOffset
        val plainHeader = ByteArray(plainHeaderSize.toInt())
        channel.readFully(plainHeaderPosition, plainHeader.size).get(plainHeader)
        val plainHeaderCrc = CRC32().run {
            update(plainHeader)
            value
        }

        val options = AES256Options(password)
        val encryptedHeader = ByteArrayOutputStream().apply {
            Coders.addEncoder(this, SevenZMethod.AES256SHA256, options).use { it.write(plainHeader) }
        }.toByteArray()
        val coderProperties =
            Coders.findByMethod(SevenZMethod.AES256SHA256).getOptionsAsProperties(options)
        val encodedHeader = buildEncodedHeader(
            packPosition = plainHeaderOffset,
            packSize = encryptedHeader.size.toLong(),
            unpackSize = plainHeaderSize,
            unpackCrc = plainHeaderCrc,
            coderProperties = coderProperties
        )

        // The ciphertext is zero padded up to the cipher block size, so it never lands short of the
        // plain header it replaces and nothing before it has to move.
        channel.writeFully(plainHeaderPosition, encryptedHeader)
        val encodedHeaderPosition = plainHeaderPosition + encryptedHeader.size
        channel.writeFully(encodedHeaderPosition, encodedHeader)
        channel.truncate(encodedHeaderPosition + encodedHeader.size)

        val encodedHeaderCrc = CRC32().run {
            update(encodedHeader)
            value
        }
        signatureHeader.putLong(
            NEXT_HEADER_OFFSET_POSITION, encodedHeaderPosition - SevenZFile.SIGNATURE_HEADER_SIZE
        )
        signatureHeader.putLong(NEXT_HEADER_SIZE_POSITION, encodedHeader.size.toLong())
        signatureHeader.putInt(NEXT_HEADER_CRC_POSITION, encodedHeaderCrc.toInt())
        val startHeaderCrc = CRC32().run {
            update(signatureHeader.array(), NEXT_HEADER_OFFSET_POSITION, START_HEADER_SIZE)
            value
        }
        signatureHeader.putInt(START_HEADER_CRC_POSITION, startHeaderCrc.toInt())
        channel.writeFully(0L, signatureHeader.array())
    }

    /**
     * Writes the streams info describing the single AES encrypted packed stream that now holds the
     * header, mirroring [SevenZOutputFile]'s own `writePackInfo` and `writeUnpackInfo`. Substreams
     * info is left out: one folder holding one stream is what the reader already assumes.
     */
    @Throws(IOException::class)
    private fun buildEncodedHeader(
        packPosition: Long,
        packSize: Long,
        unpackSize: Long,
        unpackCrc: Long,
        coderProperties: ByteArray
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val header = DataOutputStream(bytes)
        header.write(NID.kEncodedHeader)

        header.write(NID.kPackInfo)
        header.writeUint64(packPosition)
        header.writeUint64(1)
        header.write(NID.kSize)
        header.writeUint64(packSize)
        header.write(NID.kEnd)

        header.write(NID.kUnpackInfo)
        header.write(NID.kFolder)
        header.writeUint64(1)
        // Folders are inlined here rather than kept in a separate stream.
        header.write(0)
        header.writeUint64(1)
        val coderId = SevenZMethod.AES256SHA256.id
        header.write(coderId.size or CODER_FLAG_HAS_ATTRIBUTES)
        header.write(coderId)
        header.writeUint64(coderProperties.size.toLong())
        header.write(coderProperties)
        header.write(NID.kCodersUnpackSize)
        header.writeUint64(unpackSize)
        header.write(NID.kCRC)
        // "allAreDefined" == true
        header.write(1)
        header.writeInt(Integer.reverseBytes(unpackCrc.toInt()))
        header.write(NID.kEnd)

        header.write(NID.kEnd)
        header.flush()
        return bytes.toByteArray()
    }

    /** @see SevenZOutputFile.writeUint64 */
    @Throws(IOException::class)
    private fun DataOutputStream.writeUint64(value: Long) {
        var remaining = value
        var firstByte = 0
        var mask = 0x80
        var i = 0
        while (i < 8) {
            if (remaining < 1L shl 7 * (i + 1)) {
                firstByte = firstByte or (remaining ushr 8 * i).toInt()
                break
            }
            firstByte = firstByte or mask
            mask = mask ushr 1
            ++i
        }
        write(firstByte)
        while (i > 0) {
            write((0xff and remaining.toInt()))
            remaining = remaining ushr 8
            --i
        }
    }

    @Throws(IOException::class)
    private fun SeekableByteChannel.readFully(position: Long, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        position(position)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) {
                throw IOException("Unexpected end of archive while reading its header")
            }
        }
        buffer.flip()
        return buffer
    }

    @Throws(IOException::class)
    private fun SeekableByteChannel.writeFully(position: Long, bytes: ByteArray) {
        position(position)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            write(buffer)
        }
    }

    private const val START_HEADER_CRC_POSITION = 8
    private const val NEXT_HEADER_OFFSET_POSITION = 12
    private const val NEXT_HEADER_SIZE_POSITION = 20
    private const val NEXT_HEADER_CRC_POSITION = 28
    /** The next header offset, size and CRC that [START_HEADER_CRC_POSITION] covers. */
    private const val START_HEADER_SIZE = 20

    private const val CODER_FLAG_HAS_ATTRIBUTES = 0x20

    /** The header is held in memory to encrypt it, and [SevenZOutputFile] builds it that way too. */
    private const val MAX_HEADER_SIZE = 128L * 1024 * 1024
}
