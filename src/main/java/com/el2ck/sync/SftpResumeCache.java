package com.el2ck.sync;

/**
 * 【文件职责】管理 SFTP 下载的本地断点缓存，先落盘并校验长度和 SHA-256，再原子发布完整文件。
 */

import com.el2ck.util.RemoteSourceFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/** 仅管理本任务、本轮自己的缓存。先 fsync 数据，再原子提交前缀长度与 SHA-256。 */
final class SftpResumeCache implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SftpResumeCache.class);
    private static final long FLUSH_BYTES=8L*1024*1024;
    private final Path directory;
    private FileChannel lockChannel;
    private java.nio.channels.FileLock lock;
    private boolean closed;
    static final class BusyException extends RuntimeException {
        BusyException() { super("本机仍有运行占用该下载缓存，等待旧运行退出后恢复"); }
    }
    static class Prefix {
        public Prefix() { }
        public long bytes;
        public String sha256,version;
    }
    static class FileCopy {
        final Path path; final String hash;
        FileCopy(Path path,String hash) { this.path=path; this.hash=hash; }
    }
    /** 隔离真实 SFTP 会话，便于恢复前内容校验和缓存策略单元测试。 */
    interface RemoteAccess {
        RemoteSourceFile.RemoteEntry stat(String path);
        InputStream openRange(String path,long skip);
    }
    /**
     * 从断点 token 中提取末尾 32 位 UUID，作为缓存目录的唯一安全标识。
     * 断点 token 还可能包含用于 ClickHouse 查询标记的密钥前缀；该前缀绝不能写入磁盘路径。
     */
    static String cacheToken(String token) {
        if(token==null || token.length()<32) return null;
        String suffix=token.substring(token.length()-32);
        for(int i=0;i<suffix.length();i++) {
            char value=suffix.charAt(i);
            if(!((value>='0' && value<='9') || (value>='a' && value<='f'))) return null;
        }
        return suffix;
    }

    SftpResumeCache(Path root,long taskId,String token) {
        String safeToken=cacheToken(token);
        if(taskId<0 || safeToken==null) throw new IllegalArgumentException("非法缓存归属");
        Path base=root.toAbsolutePath().normalize();
        directory=base.resolve("sftp").resolve("task-"+taskId).resolve(safeToken).normalize();
        if(!directory.startsWith(base)) throw new IllegalArgumentException("缓存路径超出数据目录");
        try {
            Files.createDirectories(base);
            Path current=base;
            for(Path part:base.relativize(directory)) {
                current=current.resolve(part);
                if(Files.isSymbolicLink(current)) throw new IOException("缓存目录不能是符号链接");
                Files.createDirectories(current);
            }
            Path lockPath=directory.resolve("round.lock");
            if(Files.isSymbolicLink(lockPath)) throw new IOException("缓存锁不能是符号链接");
            lockChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try { lock=lockChannel.tryLock(); }
            catch(java.nio.channels.OverlappingFileLockException e) { throw new BusyException(); }
            if(lock==null) throw new BusyException();
        } catch(RuntimeException e) {
            if(lockChannel!=null) try { lockChannel.close(); } catch(IOException ignored) { }
            throw e;
        } catch(IOException e) {
            if(lockChannel!=null) try { lockChannel.close(); } catch(IOException ignored) { }
            throw new IllegalStateException("无法创建断点缓存目录",e);
        }
    }
/**
 * 构造并校验缓存文件路径，拒绝越界路径和符号链接。
 *
 * @param index 本次操作使用的输入参数
 * @param suffix 本次操作使用的输入参数
 * @return Path 类型的结果
 */
    private Path path(int index,String suffix) {
        if(index<0) throw new IllegalArgumentException("非法文件编号");
        Path file=directory.resolve("file-"+index+suffix);
        if(Files.isSymbolicLink(file)) throw new IllegalStateException("断点缓存文件不能是符号链接");
        return file;
    }
/**
 * 返回某页远端文件快照元数据路径。
 *
 * @param index 本次操作使用的输入参数
 * @return Path 类型的结果
 */
    Path snapshotPage(int index) { return path(index,".json"); }

    /** 清单与分页同属轮次锁保护，拒绝通过链接读写其他位置。 */
    Path snapshotManifest() {
        requireOpen();
        Path file=directory.resolve("snapshot-manifest.sha256");
        if(Files.isSymbolicLink(file)) throw new IllegalStateException("快照清单不能是符号链接");
        return file;
    }

    private void requireOpen() {
        if(closed || lock==null || !lock.isValid()) throw new IllegalStateException("快照缓存锁已失效");
    }

    /**
     * 仅用于未完成采集、尚无目标写入的轮次，由调用者在任务锁内确认状态。
     * 只删除分页及摘要清单；Integer.MAX_VALUE 是 SFTP 原文件编号，必须保留供下载续传。
     * 逐文件复核锁，不递归，也不跟随符号链接。
     */
    void resetSnapshotPages(Runnable fence) {
        requireOpen(); fence.run();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(directory)) {
            for(Path file:files) {
                String name=file.getFileName().toString();
                if(!(name.matches("file-[0-9]+\\.json") && !name.equals("file-2147483647.json"))
                        && !name.equals("snapshot-manifest.sha256")) continue;
                requireOpen(); fence.run(); cancelled();
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("快照缓存不是普通文件");
                Files.delete(file);
            }
        } catch(IOException e) { throw new IllegalStateException("清理失效快照页失败，停止重新采集",e); }
    }

    /** 数据页刷盘后追加固定长度摘要；不再把不断增长的清单反复写入 MySQL。 */
    void appendSnapshotHash(String hash) throws IOException {
        if(hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("非法快照页摘要");
        try(FileChannel file=FileChannel.open(snapshotManifest(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            ByteBuffer bytes=ByteBuffer.wrap((hash+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            while(bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
    }

    /** 旧完整快照升级时先完整写盘；提交摘要成功前数据库仍保留原数组，可安全重试。 */
    void writeSnapshotManifest(java.util.List<String> hashes) throws IOException {
        try(FileChannel file=FileChannel.open(snapshotManifest(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            for(String hash:hashes) {
                if(hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("非法快照页摘要");
                ByteBuffer bytes=ByteBuffer.wrap((hash+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                while(bytes.hasRemaining()) file.write(bytes);
            }
            file.force(true);
        }
    }

    String snapshotManifestHash() {
        try { return hash(snapshotManifest()); }
        catch(Exception e) { throw new IllegalStateException("无法校验快照摘要清单",e); }
    }
/**
 * 获取经过大小、远端版本和 SHA-256 校验的本地完整文件；存在可信缓存时直接复用。
 *
 * @param index 本次操作使用的输入参数
 * @param entry 本次操作使用的输入参数
 * @param expectedHash 本次操作使用的输入参数
 * @param connection 本次操作使用的输入参数
 * @param progress 本次操作使用的输入参数
 * @return FileCopy 类型的结果
 */
    FileCopy obtain(int index,RemoteSourceFile.RemoteEntry entry,String expectedHash,
                    Supplier<RemoteSourceFile.SftpSession> connection,LongConsumer progress) {
        return obtain(index,entry,expectedHash,connection,progress,"csv");
    }
/**
 * 获取经过大小、远端版本和 SHA-256 校验的本地完整文件；存在可信缓存时直接复用。
 *
 * @param index 本次操作使用的输入参数
 * @param entry 本次操作使用的输入参数
 * @param expectedHash 本次操作使用的输入参数
 * @param connection 本次操作使用的输入参数
 * @param progress 本次操作使用的输入参数
 * @param format 本次操作使用的输入参数
 * @return FileCopy 类型的结果
 */
    FileCopy obtain(int index,RemoteSourceFile.RemoteEntry entry,String expectedHash,
                    Supplier<RemoteSourceFile.SftpSession> connection,LongConsumer progress,String format) {
        RemoteSourceFile.SftpSession sftp=connection.get();
        return obtain(index,entry,expectedHash,new RemoteAccess() {
            @Override public RemoteSourceFile.RemoteEntry stat(String path) { return sftp.stat(path); }
            @Override public InputStream openRange(String path,long skip) { return sftp.openRange(path,skip); }
        },progress,format,true);
    }

    FileCopy obtain(int index,RemoteSourceFile.RemoteEntry entry,String expectedHash,
                    RemoteAccess sftp,LongConsumer progress,String format,boolean retryHashMismatch) {
        if(!java.util.Arrays.asList("csv","json","xlsx").contains(format)) throw new IllegalArgumentException("不支持的文件格式");
        Path ready=path(index,"."+format),part=path(index,".part"),meta=path(index,".meta");
        try {
            RemoteSourceFile.RemoteEntry current=sftp.stat(entry.path);
            sameVersion(entry,current);
            String version=SyncCheckpointStore.hash(entry.path+(char)10+entry.size+(char)10+entry.mtime);
            if(Files.isRegularFile(ready,LinkOption.NOFOLLOW_LINKS)) {
                long readySize=Files.size(ready);
                String readyHash=readySize==entry.size ? hash(ready) : null;
                boolean contentMatches=false;
                if(readySize==entry.size) {
                    if(expectedHash!=null) {
                        contentMatches=expectedHash.equals(readyHash);
                    } else {
                        // 无法读取远端不等于内容损坏；异常直接上抛，保留缓存供重连后再次验证。
                        contentMatches=remoteContentMatches(sftp,entry,ready,entry.size);
                    }
                }
                if(contentMatches) {
                    sameVersion(entry,sftp.stat(entry.path));
                    progress.accept(entry.size);
                    return new FileCopy(ready,readyHash);
                }
                Files.deleteIfExists(ready);
            }

            Prefix prefix=readPrefix(meta);
            boolean mayRetryHash=retryHashMismatch;
            while(true) {
                long offset=resumeOffset(index,sftp,entry,part,prefix,version);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                if(offset>0) digestPrefix(part,offset,digest);
                progress.accept(offset);
                try(FileChannel output=FileChannel.open(part,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                    output.truncate(offset);
                    output.position(offset);
                    long confirmed=offset;
                    long lastFlush=System.nanoTime();
                    try(InputStream input=sftp.openRange(entry.path,offset)) {
                        byte[] buffer=new byte[128*1024];
                        while(offset<entry.size) {
                            cancelled();
                            int count;
                            try { count=input.read(buffer,0,(int)Math.min(buffer.length,entry.size-offset)); }
                            catch(IOException e) { throw new RemoteSourceFile.SftpConnectionException("SFTP 下载连接中断",e); }
                            if(count<0) throw new IllegalStateException("SFTP 文件提前结束，远端版本已变化");
                            if(count==0) continue;
                            ByteBuffer data=ByteBuffer.wrap(buffer,0,count);
                            while(data.hasRemaining()) output.write(data);
                            digest.update(buffer,0,count);
                            offset+=count;
                            if(offset-confirmed>=FLUSH_BYTES || System.nanoTime()-lastFlush>=2_000_000_000L) {
                                persist(output,index,offset,digest,version);
                                confirmed=offset;
                                lastFlush=System.nanoTime();
                                progress.accept(offset);
                            }
                        }
                        int extra;
                        try { extra=input.read(); }
                        catch(IOException e) { throw new RemoteSourceFile.SftpConnectionException("确认 SFTP 文件结束失败",e); }
                        if(extra!=-1) throw new IllegalStateException("SFTP 文件在下载期间增长，停止本轮同步");
                    } finally {
                        // 网络异常也保存已经落盘且可校验的前缀；不提交任何目标写入位置。
                        persist(output,index,offset,digest,version);
                    }
                }
                sameVersion(entry,sftp.stat(entry.path));
                String downloadedHash=hex(digest.digest());
                if(expectedHash!=null && !expectedHash.equals(downloadedHash)) {
                    discardPrefix(index);
                    if(mayRetryHash) {
                        mayRetryHash=false;
                        prefix=null;
                        progress.accept(0);
                        continue;
                    }
                    throw new IllegalStateException("重新下载的文件内容与原断点摘要不一致，停止续传");
                }
                move(part,ready);
                Files.deleteIfExists(meta);
                progress.accept(entry.size);
                return new FileCopy(ready,downloadedHash);
            }
        } catch(RuntimeException e) {
            throw e;
        } catch(Exception e) {
            throw new IllegalStateException("SFTP 断点缓存处理失败",e);
        }
    }

    /**
     * 只恢复同时通过本地摘要和远端完整前缀比较的断点。path、size、mtime 不能证明
     * 内容相同，首尾抽样也会漏掉中间修改；仅确认内容不一致时从 0 下载。
     * 校验异常或取消时保留 part/meta，让上层重试或下次运行继续使用。
     */
    private long resumeOffset(int index,RemoteAccess sftp,RemoteSourceFile.RemoteEntry entry,
                              Path part,Prefix prefix,String version) throws Exception {
        if(prefix==null) {
            discardPrefix(index);
            return 0;
        }
        if(!version.equals(prefix.version) || prefix.bytes<0
                || prefix.bytes>entry.size || prefix.sha256==null
                || !Files.isRegularFile(part,LinkOption.NOFOLLOW_LINKS)
                || Files.size(part)<prefix.bytes) {
            discardPrefix(index);
            return 0;
        }
        if(!prefix.sha256.equals(hashPrefix(part,prefix.bytes))) {
            discardPrefix(index);
            return 0;
        }
        if(remoteContentMatches(sftp,entry,part,prefix.bytes)) return prefix.bytes;
        LOG.warn("SFTP 续传前缀内容不一致，旧断点将从 0 重下 index={}",index);
        discardPrefix(index);
        return 0;
    }

    private Prefix readPrefix(Path meta) {
        if(!Files.isRegularFile(meta,LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if(Files.size(meta)>=4096) return null;
            return JSON.readValue(Files.readAllBytes(meta),Prefix.class);
        } catch(Exception e) {
            return null;
        }
    }

    /**
     * 流式比较整个指定前缀；完整缓存缺少可信摘要时，bytes 等于完整文件长度。
     * 每次只使用两个 64 KiB 缓冲区，比较长度使用 long 以支持超过 2 GiB 的文件。
     * 此校验会额外读取远端已下载部分，但不能以抽样替代，否则会接受新旧内容拼接。
     * 源文件仍需在校验和下载期间保持稳定；普通 SFTP 不提供不可变快照语义。
     */
    private static boolean remoteContentMatches(RemoteAccess sftp,RemoteSourceFile.RemoteEntry entry,
                                                Path local,long bytes) throws IOException {
        if(bytes<0 || bytes>entry.size) return false;
        try(FileChannel input=FileChannel.open(local,StandardOpenOption.READ)) {
            if(input.size()<bytes) return false;
            if(bytes==0) return true;
            return rangeMatches(sftp,entry.path,input,0,bytes);
        }
    }

    private static boolean rangeMatches(RemoteAccess sftp,String remotePath,FileChannel local,
                                        long offset,long bytes) throws IOException {
        try(InputStream remote=validationStream(sftp,remotePath,offset)) {
            local.position(offset);
            byte[] localBytes=new byte[64*1024];
            byte[] remoteBytes=new byte[64*1024];
            long remaining=bytes;
            while(remaining>0) {
                cancelled();
                int wanted=(int)Math.min(remaining,localBytes.length);
                int localRead=readFully(local,localBytes,wanted);
                int remoteRead=readFully(remote,remoteBytes,wanted);
                if(localRead!=wanted || remoteRead!=wanted) return false;
                for(int i=0;i<wanted;i++) if(localBytes[i]!=remoteBytes[i]) return false;
                remaining-=wanted;
            }
            return true;
        }
    }

    /** 只把远端读流的 I/O 故障标记为可重试，不能把本地磁盘异常或主动取消吞成内容失配。 */
    private static InputStream validationStream(RemoteAccess sftp,String path,long offset) {
        return new FilterInputStream(sftp.openRange(path,offset)) {
            @Override public int read(byte[] bytes,int start,int length) {
                try { return in.read(bytes,start,length); }
                catch(IOException e) { throw new RemoteSourceFile.SftpConnectionException("SFTP 内容校验连接中断，已保留缓存",e); }
            }
            @Override public void close() {
                try { super.close(); }
                catch(IOException e) { throw new RemoteSourceFile.SftpConnectionException("SFTP 内容校验连接关闭失败，已保留缓存",e); }
            }
        };
    }

    private static int readFully(FileChannel channel,byte[] bytes,int length) throws IOException {
        ByteBuffer buffer=ByteBuffer.wrap(bytes,0,length);
        while(buffer.hasRemaining()) {
            int count=channel.read(buffer);
            if(count<0) break;
            if(count==0) return buffer.position();
        }
        return buffer.position();
    }

    private static int readFully(InputStream input,byte[] bytes,int length) throws IOException {
        int total=0;
        while(total<length) {
            cancelled();
            int count=input.read(bytes,total,length-total);
            // 校验范围由 stat 固定；短读可能来自掉线，不能据此删除已验证的本地断点。
            if(count<0) throw new RemoteSourceFile.SftpConnectionException("SFTP 内容校验提前结束，已保留缓存",new EOFException());
            if(count==0) throw new RemoteSourceFile.SftpConnectionException("SFTP 内容校验未读到数据，已保留缓存",new IOException("zero-byte read"));
            total+=count;
        }
        return total;
    }

    private void discardPrefix(int index) throws IOException {
        Files.deleteIfExists(path(index,".part"));
        Files.deleteIfExists(path(index,".meta"));
        Files.deleteIfExists(path(index,".meta.tmp"));
    }
/**
 * 把已校验的临时文件原子发布为正式缓存，并持久化摘要元数据。
 *
 * @param output 本次操作使用的输入参数
 * @param index 本次操作使用的输入参数
 * @param bytes 本次操作使用的输入参数
 * @param digest 本次操作使用的输入参数
 * @param version 本次操作使用的输入参数
 */
    private void persist(FileChannel output,int index,long bytes,MessageDigest digest,String version) throws Exception {
        output.force(true);
        Prefix prefix=new Prefix(); prefix.bytes=bytes; prefix.version=version;
        prefix.sha256=hex(((MessageDigest)digest.clone()).digest());
        Path temporary=path(index,".meta.tmp");
        try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer value=ByteBuffer.wrap(JSON.writeValueAsBytes(prefix)); while(value.hasRemaining()) file.write(value); file.force(true);
        }
        move(temporary,path(index,".meta"));
    }
/**
 * 确认远端文件大小和修改时间与当前下载所依据的版本一致。
 *
 * @param a 本次操作使用的输入参数
 * @param b 本次操作使用的输入参数
 */
    private static void sameVersion(RemoteSourceFile.RemoteEntry a,RemoteSourceFile.RemoteEntry b) {
        if(a.size!=b.size || !java.util.Objects.equals(a.mtime,b.mtime))
            throw new IllegalStateException("SFTP 文件大小或修改时间已变化，旧下载断点不能继续: "+a.path
                    +"（原大小="+a.size+"，当前大小="+b.size+" 字节；原修改时间="+a.mtime+"，当前修改时间="+b.mtime
                    +"，时间单位为 epoch 秒）。请等待文件生成完成；当前重新传输操作会全量重建目标表");
    }
/**
 * 执行原子移动并处理跨文件系统或平台差异。
 *
 * @param source 本次操作使用的输入参数
 * @param target 本次操作使用的输入参数
 */
    private static void move(Path source,Path target) throws IOException {
        try { Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(source,target,StandardCopyOption.REPLACE_EXISTING); }
    }
/**
 * 计算完整文件 SHA-256。
 *
 * @param path 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private static String hash(Path path) throws Exception { return hashPrefix(path,Files.size(path)); }
/**
 * 计算缓存前缀的 SHA-256，用于恢复下载前验证已有字节。
 *
 * @param path 本次操作使用的输入参数
 * @param bytes 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private static String hashPrefix(Path path,long bytes) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); digestPrefix(path,bytes,digest); return hex(digest.digest());
    }
/**
 * 流式读取指定前缀并更新摘要，避免把大文件整体载入内存。
 *
 * @param path 本次操作使用的输入参数
 * @param bytes 本次操作使用的输入参数
 * @param digest 本次操作使用的输入参数
 */
    private static void digestPrefix(Path path,long bytes,MessageDigest digest) throws IOException {
        try(InputStream input=Files.newInputStream(path)) {
            byte[] buffer=new byte[128*1024]; long remaining=bytes;
            while(remaining>0) {
                cancelled(); int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(n<0) throw new EOFException("本地下载前缀不完整"); digest.update(buffer,0,n); remaining-=n;
            }
        }
    }
/**
 * 把摘要字节转换为小写十六进制字符串。
 *
 * @param bytes 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(); for(byte b:bytes) result.append(String.format("%02x",b&255)); return result.toString();
    }
/**
 * 删除缓存文件并忽略目标已不存在的情况。
 *
 * @param index 本次操作使用的输入参数
 */
    void remove(int index) {
        try { for(String suffix:new String[]{".csv",".json",".xlsx",".part",".meta",".meta.tmp"}) Files.deleteIfExists(path(index,suffix)); }
        catch(Exception e) { org.slf4j.LoggerFactory.getLogger(SftpResumeCache.class).warn("SFTP 已完成文件的缓存清理失败 index={}",index); }
    }
/**
 * 清理当前任务的临时缓存；锁文件保留到关闭阶段以维持单实例互斥。
 *
 */
    void clean() {
        if(!closed) throw new IllegalStateException("先关闭缓存读写再清理");
        try {
            if(Files.isSymbolicLink(directory)) return;
            try(DirectoryStream<Path> files=Files.newDirectoryStream(directory)) {
                for(Path file:files) if(file.getFileName().toString().matches("file-[0-9]+\\.(csv|part|meta|meta\\.tmp|json|xlsx)")
                        || file.getFileName().toString().equals("snapshot-manifest.sha256")) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory.resolve("round.lock"));
            Files.deleteIfExists(directory);
        } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(SftpResumeCache.class).warn("SFTP 本轮缓存清理未完成"); }
    }
    @Override public void close() {
        if(closed) return;
        closed=true;
        if(lock!=null) try { lock.release(); } catch(IOException ignored) { }
        if(lockChannel!=null) try { lockChannel.close(); } catch(IOException ignored) { }
    }
/**
 * 检查线程中断，让下载和校验能够在批次边界及时停止。
 *
 */
    private static void cancelled() { if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("文件下载或校验已取消"); }
}
