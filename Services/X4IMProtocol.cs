using System.Buffers.Binary;
using System.Text;

namespace GSDJX4DoubleSysFserv.Services;

public static class X4IMProtocol
{
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

        // Version + Type（type 固定为 0x00，与 ESP32 和 main.js 对齐）
        header[4] = 0x02;
        header[5] = 0x00;

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
    /// 创建图片帧头（BMP/PNG/JPG 等）- 确保使用正确的图片类型，不使用 TXT 类型
    /// </summary>
    /// <param name="imageData">图片二进制数据</param>
    /// <param name="fileName">文件名（如 page_0.png, qr.bmp）</param>
    /// <param name="imageFlags">图片类型标志（如 FLAG_TYPE_PNG, FLAG_TYPE_BMP）</param>
    /// <param name="sd">存储标识：0=littlefs, 1=SD卡</param>
    /// <returns>包含帧头和部分数据的第一个分片</returns>
    public static byte[] CreateImageHeader(int imageSize, string fileName = "page_0.bmp", ushort imageFlags = FLAG_TYPE_BMP, uint sd = 0)
    {
        // 确保不是 TXT 类型
        if (imageFlags == FLAG_TYPE_TXT)
        {
            throw new ArgumentException("图片不应使用 TXT 类型标志！请使用 FLAG_TYPE_PNG/BMP/JPG 等");
        }

        return CreateHeader((uint)imageSize, fileName, sd, imageFlags);
    }

    /// <summary>
    /// 分片图片数据（用于 BLE 分包传输，MTU 512 字节）
    /// 第一个分片包含 32 字节帧头 + 480 字节数据；BMP 传输完成后需发送 SHOW_PAGE 命令触发显示（无需 EOF）。
    /// 后续分片为纯数据，每片最多 512 字节
    /// </summary>
    public static List<byte[]> ChunkImageData(byte[] imageData, ushort imageFlags = FLAG_TYPE_BMP, string fileName = "page_0.bmp", uint sd = 0, int mtu = 512)
    {
        var chunks = new List<byte[]>();

        // 确保不是 TXT 类型
        if (imageFlags == FLAG_TYPE_TXT)
        {
            throw new ArgumentException("图片不应使用 TXT 类型标志！请使用 FLAG_TYPE_PNG/BMP/JPG 等");
        }

        var header = CreateImageHeader(imageData.Length, fileName, imageFlags, sd);

        // 第一个分片：帧头 + 部分数据（MTU 512 = 32 header + 480 data）
        int firstChunkDataSize = Math.Min(mtu - 32, imageData.Length);
        var firstChunk = new byte[32 + firstChunkDataSize];
        Array.Copy(header, 0, firstChunk, 0, 32);
        Array.Copy(imageData, 0, firstChunk, 32, firstChunkDataSize);
        chunks.Add(firstChunk);

        // 后续分片：纯数据（每片最多 MTU 字节）
        int offset = firstChunkDataSize;
        while (offset < imageData.Length)
        {
            int remainingSize = imageData.Length - offset;
            int currentChunkSize = Math.Min(mtu, remainingSize);
            var chunk = new byte[currentChunkSize];
            Array.Copy(imageData, offset, chunk, 0, currentChunkSize);
            chunks.Add(chunk);
            offset += currentChunkSize;
        }

        return chunks;
    }

    /// <summary>
    /// 构造 SHOW_PAGE 命令负载（BMP/图片传输完成后调用以触发显示）
    /// </summary>
    public static byte[] CreateShowPageCommand(byte pageIndex = 0)
    {
        return new byte[] { CMD_SHOW_PAGE, pageIndex };
    }
}
