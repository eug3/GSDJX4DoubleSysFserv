using System.Buffers.Binary;
using System.Text;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// X4IM v2 协议实现
/// 
/// 协议格式 (ESP32 端当前解析的 v2 帧头，32 字节):
///   magic (4B)   = "X4IM"
///   version (1B) = 0x02
///   type   (1B)  = 文件类型（BMP/JPG/TXT 等）
///   flags  (2B)  = 文件标志位（如 TXT=0x0004, BMP=0x0020）
///   payload_size (4B, LE) = 数据大小（不含 EOF）
///   sd/seq (4B, LE)       = 固件当前用于存储/序号，保留 0 即可
///   name (16B)            = UTF-8 文件名/书籍 ID（最多 15B，\0 结尾）
/// 
/// 数据部分:
///   直接发送原始数据（TXT文本等）
///   可以分多次发送，ESP32 会自动拼接
///   payload_size 不包含 EOF 标记大小
/// 
/// EOF 标记（仅用于 TXT）:
///   传输完成后单独发送：0x00 0x45 0x4F 0x46 0x0A (\x00EOF\n)
///   或缩短版：0x00 0x45 0x4F 0x46 (\x00EOF，不含 \n)
///   ESP32 根据这个标记触发文件显示（BMP/PNG 不需要 EOF）
/// </summary>
public static class X4IMProtocol
{
    // 文件类型常量
    public const byte TYPE_BMP = 0x01;
    public const byte TYPE_PNG = 0x02;
    public const byte TYPE_JPG = 0x03;
    public const byte TYPE_JPEG = 0x04;
    public const byte TYPE_TXT = 0x10;
    public const byte TYPE_EPUB = 0x11;
    public const byte TYPE_PDF = 0x12;
    public const byte TYPE_BINARY = 0x7F;

    // 标志位定义
    public const ushort FLAG_STORAGE_SD = 0x0100;
    public const ushort FLAG_STORAGE_LITTLEFS = 0x0000;
    public const ushort FLAG_TYPE_PDF = 0x0001;
    public const ushort FLAG_TYPE_EPUB = 0x0002;
    public const ushort FLAG_TYPE_TXT = 0x0004;
    public const ushort FLAG_TYPE_PNG = 0x0008;
    public const ushort FLAG_TYPE_JPG = 0x0010;
    public const ushort FLAG_TYPE_BMP = 0x0020;

    // 命令类型常量
    public const byte CMD_SHOW_PAGE = 0x80;
    public const byte CMD_NEXT_PAGE = 0x81;
    public const byte CMD_PREV_PAGE = 0x82;
    public const byte CMD_REFRESH = 0x83;
    public const byte CMD_CLEAR = 0x84;
    public const byte CMD_DELETE_PAGE = 0x85;
    public const byte CMD_DELETE_ALL = 0x86;
    public const byte CMD_GET_STATUS = 0x87;
    public const byte CMD_SET_BOOK_ID = 0x88;
    public const byte CMD_SLEEP = 0x89;
    public const byte CMD_WAKE = 0x8A;
    public const byte CMD_FILE_NOTIFY = 0x8B;
    public const byte CMD_LIST_FILES = 0x8E;
    public const byte CMD_DELETE_FILE = 0x8F;
    public const byte CMD_RENAME_FILE = 0x90;
    public const byte CMD_CLEAR_BOOKS = 0x91;
    public const byte CMD_CREATE_DIR = 0x92;
    public const byte CMD_GET_STORAGE_INFO = 0x93;
    public const byte CMD_READ_FILE = 0x94;
    public const byte CMD_FILE_DATA = 0x95;
    public const byte CMD_POSITION_REPORT = 0x96;
    public const byte CMD_SHOW_FILE = 0x99;

    // EOF 标记：\x00EOF\n
    public static readonly byte[] EOF_MARKER = new byte[] { 0x00, 0x45, 0x4F, 0x46, 0x0A };

    /// <summary>
    /// 创建 X4IM v2 协议帧头
    /// </summary>
    /// <param name="payloadSize">数据大小（字节，不含 EOF）</param>
    /// <param name="bookId">文件名/书籍ID（最多15字符）</param>
    /// <param name="sd">存储标识：0=littlefs, 1=SD卡</param>
    /// <param name="flags">文件标志位（默认 TXT）</param>
    /// <returns>32 字节的帧头</returns>
    public static byte[] CreateHeader(uint payloadSize, string bookId = "", uint sd = 0, ushort flags = FLAG_TYPE_TXT)
    {
        var header = new byte[32];

        // Magic: "X4IM"
        header[0] = 0x58;
        header[1] = 0x34;
        header[2] = 0x49;
        header[3] = 0x4D;

        // Version + Type（保持与 ESP32 解析一致: version 在 [4]，type 在 [5]）
        header[4] = 0x02;
        header[5] = FlagsToType(flags);

        // Flags（小端序）
        header[6] = (byte)(flags & 0xFF);
        header[7] = (byte)((flags >> 8) & 0xFF);

        // Payload size (小端序)
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(8, 4), payloadSize);

        // Storage ID (小端序)
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(12, 4), sd);

        // Book ID (UTF-8, 最多15字符 + null terminator)
        if (!string.IsNullOrEmpty(bookId))
        {
            var idBytes = Encoding.UTF8.GetBytes(bookId.Substring(0, Math.Min(bookId.Length, 15)));
            Array.Copy(idBytes, 0, header, 16, idBytes.Length);
            if (idBytes.Length < 16)
            {
                header[16 + idBytes.Length] = 0;
            }
        }

        return header;
    }

    /// <summary>
    /// 将文本转换为 X4IM v2 帧（包含帧头和数据）
    /// </summary>
    public static byte[] CreateTxtFrame(string text, string bookId = "", uint sd = 0)
    {
        var textBytes = Encoding.UTF8.GetBytes(text);
        var header = CreateHeader((uint)textBytes.Length, bookId, sd, FLAG_TYPE_TXT);
        
        var frame = new byte[32 + textBytes.Length];
        Array.Copy(header, 0, frame, 0, 32);
        Array.Copy(textBytes, 0, frame, 32, textBytes.Length);
        
        return frame;
    }

    /// <summary>
    /// 分片文本数据（用于分包传输）
    /// </summary>
    public static List<byte[]> ChunkTextData(string text, int chunkSize = 480, string bookId = "", uint sd = 0)
    {
        var chunks = new List<byte[]>();
        var textBytes = Encoding.UTF8.GetBytes(text);
        var header = CreateHeader((uint)textBytes.Length, bookId, sd, FLAG_TYPE_TXT);

        // 第一个分包：帧头 + 部分数据
        int firstChunkDataSize = Math.Min(chunkSize - 32, textBytes.Length);
        var firstChunk = new byte[32 + firstChunkDataSize];
        Array.Copy(header, 0, firstChunk, 0, 32);
        Array.Copy(textBytes, 0, firstChunk, 32, firstChunkDataSize);
        chunks.Add(firstChunk);

        // 后续分包：纯数据
        int offset = firstChunkDataSize;
        while (offset < textBytes.Length)
        {
            int remainingSize = textBytes.Length - offset;
            int currentChunkSize = Math.Min(chunkSize, remainingSize);
            var chunk = new byte[currentChunkSize];
            Array.Copy(textBytes, offset, chunk, 0, currentChunkSize);
            chunks.Add(chunk);
            offset += currentChunkSize;
        }

        return chunks;
    }

    /// <summary>
    /// 从 flags 推导协议 type 字节（保持与 ESP32 侧一致）。
    /// </summary>
    private static byte FlagsToType(ushort flags)
    {
        if ((flags & FLAG_TYPE_BMP) != 0) return TYPE_BMP;
        if ((flags & FLAG_TYPE_PNG) != 0) return TYPE_PNG;
        if ((flags & FLAG_TYPE_JPG) != 0) return TYPE_JPG;
        if ((flags & FLAG_TYPE_TXT) != 0) return TYPE_TXT;
        if ((flags & FLAG_TYPE_EPUB) != 0) return TYPE_EPUB;
        if ((flags & FLAG_TYPE_PDF) != 0) return TYPE_PDF;
        return TYPE_BINARY;
    }
}
