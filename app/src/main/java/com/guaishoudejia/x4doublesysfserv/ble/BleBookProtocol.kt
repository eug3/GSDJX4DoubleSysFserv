package com.guaishoudejia.x4doublesysfserv.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BleBookProtocol {
    const val TYPE_REQUEST: Int = 0x01
    const val TYPE_DATA: Int = 0x02
    const val TYPE_END: Int = 0x03
    const val TYPE_ACK: Int = 0x04
    const val TYPE_ERROR: Int = 0xFF

    /** Device-side expects 48 * 1024 bytes per page. */
    const val PAGE_SIZE_BYTES: Int = 48 * 1024

    /**
     * Packed header layout (must match device: ble_book_protocol.h, BLE_PACKED).
     * type(1) + book_id(2) + page_num(2) + reserved(2) + data_size(4) = 11 bytes.
     */
    const val DATA_HEADER_SIZE: Int = 11

    /** offset(4) + chunk_size(2) = 6 bytes */
    const val DATA_CHUNK_FIXED_SIZE: Int = DATA_HEADER_SIZE + 6

    /**
     * Keep payload within common ATT payload size when MTU=247 => 244 bytes.
     * 244 - fixed(17) = 227.
     */
    const val MAX_DATA_BYTES_PER_CHUNK: Int = 227

    data class Request(val bookId: Int, val startPage: Int, val pageCount: Int)

    fun parseRequest(value: ByteArray): Request? {
        // type(1) + book_id(2) + start_page(2) + page_count(1) + reserved(2) = 8 bytes
        if (value.size < 8) return null
        val bb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val type = bb.get(0).toInt() and 0xFF
        if (type != TYPE_REQUEST) return null
        bb.position(1)
        val bookId = bb.short.toInt() and 0xFFFF
        val startPage = bb.short.toInt() and 0xFFFF
        val pageCount = bb.get().toInt() and 0xFF
        return Request(bookId = bookId, startPage = startPage, pageCount = pageCount.coerceIn(1, 5))
    }

    fun buildDataChunk(
        bookId: Int,
        pageNum: Int,
        pageSize: Int,
        offset: Int,
        data: ByteArray,
        dataOffset: Int,
        dataLen: Int,
    ): ByteArray {
        require(dataLen in 0..MAX_DATA_BYTES_PER_CHUNK)

        val out = ByteArray(DATA_CHUNK_FIXED_SIZE + dataLen)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(TYPE_DATA.toByte())
        bb.putShort(bookId.toShort())
        bb.putShort(pageNum.toShort())
        bb.putShort(0) // reserved
        bb.putInt(pageSize)
        bb.putInt(offset)
        bb.putShort(dataLen.toShort())
        if (dataLen > 0) {
            System.arraycopy(data, dataOffset, out, DATA_CHUNK_FIXED_SIZE, dataLen)
        }
        return out
    }

    fun buildEnd(bookId: Int, lastPage: Int): ByteArray {
        val out = ByteArray(1 + 2 + 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(TYPE_END.toByte())
        bb.putShort(bookId.toShort())
        bb.putShort(lastPage.toShort())
        return out
    }
}
