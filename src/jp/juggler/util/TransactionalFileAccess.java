/*
	複数のプロセスからデータファイルの読み書きを行うためのクラスです。
	
	データの読み込みには byte[] load_if_update() を使います。
	まだデータを読んだことがないか、最後に読んだあとに変更されていればデータを読み込みます。
	
	データの書き出しには  transaction(TransactionProc) を呼び出します。
	古いデータを加工して新しいデータを返すようなコードを TransactionProc#update に 実装してください。
	
	特徴
	- 読み書きの際に flock (javaのFileLock) を取得して排他を行います。
	- ヘッダ部分をmmap(javaの MappedByteBuffer)でメモリにマッピングして、更新チェックの負荷を下げています。
	- データのSHA-1ダイジェストをヘッダに格納して、ロード時にチェックを行います。
	- 保存時にバックアップファイルを作成するのでデータ破損に強い？かもしれません。破損の仕方にもよりますが。

	注意点
	- このクラスは実際にはデータファイルとバックアップファイルの２つのファイルを作成/更新します
	- MappedByteBuffer は明示的に unmapを行うことができません。gcまかせです。
	- MappedByteBuffer.force() の結果が別プロセス上のマッピングにすぐに伝達されるかどうかは未定義です。
	- ファイル全体がメモリに収まるような用途しか想定してません。
*/

package jp.juggler.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import android.util.Log;

public class TransactionalFileAccess {
	static final String TAG="TransactionalFileAccess";
	public static boolean debug = false;

	// ダイジェスト計算に使うアルゴリズム
	public static final String digest_type = "SHA-1";

	// 仮想メモリのページサイズ。実際には FileChannel#map が適当に調整してくれるはず…
	public static final int pagesize = 4096;

	// トランザクションのデータ計算インタフェース
	public interface TransactionProc{
		byte[] update(byte[] old);
	}
	
	////////////////////////////////////////
	// 変数
	
	// ファイルのパーミッション
	public final int permission;

	// データファイル
	public final File        datafile;
	private RandomAccessFile datafile_handle;
	private FileChannel      datafile_channel;
	private FileLock         datafile_lock;
	private MappedByteBuffer datafile_map;

	// バックアップファイル
	public final File        backupfile;
	private RandomAccessFile backupfile_handle;
	private FileChannel      backupfile_channel;

	// save_sub や validate_file で使うバッファ
	ByteBuffer bb_tmp = ByteBuffer.allocate(pagesize);
	
	// 最後にロードしたデータ
	private int last_version = -1;
	private int last_hash_length = -1;
	private byte[] last_hash = new byte[pagesize];
	private byte[] last_data;

	/////////////////////////////////////////////////////

	public TransactionalFileAccess(String path,int permission,boolean bOpen) throws IOException{
		this.datafile = new File(path);
		this.backupfile = new File(path+".bak" );
		this.permission = permission;
		if(bOpen) open();
	}

	/*package access*/  File getDataFile(){ return datafile;}
	/*package access*/  File getBackupFile(){ return backupfile;}
	/*package access*/  int getPermission(){ return permission;}
	
	// 開く
	public synchronized void open() throws IOException{
		if( datafile_handle != null ) throw new IllegalStateException("already open.");

		try{
			//
			datafile_handle = new RandomAccessFile(datafile, "rw");
			set_permission(datafile.getPath(),permission);
			datafile_channel = datafile_handle.getChannel();
			//
			backupfile_handle = new RandomAccessFile(backupfile, "rw");
			set_permission(backupfile.getPath(),permission);
			backupfile_channel = backupfile_handle.getChannel();
			//
			lock();
			try{
				meta_buffer_map();
			}finally{
				unlock();
			}
		}catch(IOException ex){
			close();
			throw ex;
		}
	}
	
	// 閉じる
	public synchronized void close(){
		unlock();
		meta_buffer_unmap();

		if( datafile_channel != null ){
			try{ datafile_channel.close(); }catch(Throwable ex){}
			datafile_channel = null;
		}
		if( datafile_handle != null ){
			try{ datafile_handle.close(); } catch(Throwable ex){}
			datafile_handle = null; 
		}
		if( backupfile_channel != null ){
			try{ backupfile_channel.close(); }catch(Throwable ex){}
			backupfile_channel = null;
		}
		if( backupfile_handle != null ){
			try{ backupfile_handle.close(); } catch(Throwable ex){}
			backupfile_handle = null; 
		}
	}
	
	// 閉じて、データとメタデータを削除して、開き直す
	public synchronized void create() throws IOException {
		close();
		datafile.delete();
		backupfile.delete();
		
		open();
	}


	// データの強制ロード
	public synchronized byte[] load() throws IOException{
		lock();
		try{
			return load_sub();
		}finally{
			unlock();
		}
	}

	// データが更新されていればロード,でなければnullを返す
	public synchronized byte[] load_if_update() throws IOException{
		// ロック前にversionだけ見て大雑把に確認する
		int version = datafile_map.getInt(4);
		if( version == last_version ) return null;
		// ロックして確認しなおしてロード
		lock();
		try{
			return isMetaChanged() ? load_sub() : null;
		}finally{
			unlock();
		}
	}
	
	// 最後に読んだデータを取得
	public synchronized byte[] getLastLoad(){
		return last_data;
	}

	// transaction update 
	public synchronized void transaction(TransactionProc proc) throws IOException {
		lock();
		try{
			byte[] old_data = last_data;
			try{
				if( isMetaChanged() ) old_data = load_sub();
			}catch(FileNotFoundException ex){
				old_data = null; // ファイルがない場合
			}
			// update
			byte[] new_data = proc.update(old_data);
			save_sub(new_data);
		}finally{
			unlock();
		}
	}
	
	/////////////////////////////////////////////////////////////

	// データファイルが正常か確認する
	private final boolean validate_file( FileChannel fc ,String name){
		try{
			if( fc.size() < pagesize ){
				Log.e(TAG,String.format("%s: too small size: %s",name,fc.size()));
				return false;
			}
			// ヘッダ部分を読む
			ByteBuffer b = bb_tmp;
			b.clear();
			while( b.remaining() > 0 ) fc.read(b);
			// ヘッダの内容をパース
			b.position(0);
			int data_length = b.getInt();
			@SuppressWarnings("unused")
			int version = b.getInt(); 
			int digest_len = b.getInt();
			byte[] digest = new byte[digest_len]; b.get( digest );
			// データを読む
			byte[] data = new byte[data_length];
			b = ByteBuffer.wrap(data);
			fc.position(pagesize);
			while( b.remaining() > 0 ){
				int delta =fc.read(b);
				if(delta <= 0 ){
					Log.e(TAG,String.format("%s: data size not match. read=%s remain=%s",name,b.position(),b.remaining() ));
					return false;
				}
			}
			if( data_length > 0 ){
				// ダイジェストを比較する
				byte[] digest_real = check_digest(data);
				if( digest_real.length != digest_len ){
					Log.e(TAG,String.format("%s: digest size not match. header=%s data=%s",name,digest_len,digest_real.length  ));
					return false;
				}
				for(int i=0;i<digest_len;++i){
					if( digest[i] != digest_real[i] ){
						Log.e(TAG,String.format("%s: digest data not match.",name  ));
						return false;
					}
				}
			}
			// OK.
			return true;
		}catch(Throwable ex){
			ex.printStackTrace();
			return false;
		}
	}
	
	// データファイルとバックアップファイルを確認して、必要ならリストアやデータの初期化を行う
	private void restore_data() throws IOException{
		//
		if( validate_file( datafile_channel ,datafile.getName()) ) return;
		
		if( validate_file( backupfile_channel,backupfile.getName() ) ){
			Log.w(TAG,"restore from back-up file..");
			int length = (int)backupfile_channel.size();
			backupfile_channel.position(0);
			datafile_channel.position(0);
			int nCopy = 0;
			ByteBuffer b = ByteBuffer.allocate(16384);
			while( nCopy < length ){
				b.clear();
				int nRead =  backupfile_channel.read(b);
				if( nRead <= 0 ) throw new RuntimeException("backup data broken: unexpected EOF");
				int nWrite = 0;
				b.flip();
				while( nWrite < nRead ){
					int delta = datafile_channel.write(b);
					if( delta <= 0 ) throw new RuntimeException("write failed.");
					nWrite += delta;
				}
				nCopy += nWrite;
			}
			datafile_channel.truncate(length);
			datafile_channel.force(true);
			Log.w(TAG,String.format("restore data complete. copy %s bytes.",length));
			return;
		}
		
		Log.w(TAG,String.format("initialize data file."));
		datafile_channel.truncate(pagesize);
		datafile_channel.position(0);
		// 初期データはゼロフィル
		ByteBuffer buffer = ByteBuffer.wrap(new byte[pagesize]);
		buffer.position(0);
		while( buffer.remaining() > 0 ){
			datafile_channel.write(buffer);
		}
		datafile_channel.force(true);
		Log.w(TAG,String.format("initialize data file complete."));
	}
	//////////////////////////////////////////////////
	// mmap 
	
	private void meta_buffer_map() throws IOException{
		if( datafile_map == null ){
			restore_data();
			//
			last_version = 0;
			last_hash_length = -1;
			//
			datafile_map = datafile_channel.map(FileChannel.MapMode.READ_WRITE,0,pagesize);
			datafile_map.load();
			if(debug) Log.d(TAG,"header mapping start");
		}
	}
	
	private void meta_buffer_unmap(){
		if( datafile_map != null ){
			datafile_map =null;
			System.gc();
			// unmap を明示的に行うメソッドがない
			// GCがファイナライズを省略したらリークしてしまう？
			if(debug) Log.d(TAG,"header mapping end");
		}
	}

	///////////////////////////////////////////////////
	// flock
	// ロック状態の入れ子には対応していないので注意

	/*package access*/ synchronized void lock() {
		if( datafile_lock == null ){
			try{
				datafile_lock = datafile_channel.lock();
				if(debug) Log.d(TAG,"flock start");
			}catch(Throwable ex){
				ex.printStackTrace();
				throw new RuntimeException("lock failed.",ex);
			}
		}
	}

	/*package access*/ synchronized void unlock(){
		if( datafile_lock != null ){
			try{ datafile_lock.release(); }catch(Throwable ex){}
			datafile_lock = null;
			if(debug) Log.d(TAG,"flock end");
		}
	}

	///////////////////////////////////////////////////


	// メタデータの更新 をチェック
	private boolean isMetaChanged(){
		int version = datafile_map.getInt(4);
		if( version != last_version ) return true;
		
		int hash_length = datafile_map.getInt(8);
		if( hash_length != last_hash_length ) return true;

		datafile_map.position(12);
		for(int i=0;i<hash_length;++i){
			if( last_hash[i] == datafile_map.get() ) continue;
			return true;
		}
		return false;
	}
	
	// データのロード(内部処理のみで、ロックを行わない)
	private byte[] load_sub() throws IOException{
		// load metadata
		datafile_map.position(0);
		int length_data  = datafile_map.getInt();
		last_version	 = datafile_map.getInt();
		last_hash_length = datafile_map.getInt();
		datafile_map.get(last_hash,0,last_hash_length);

		if(debug) Log.d(TAG,String.format( "load: datalen=%d,version=%d,digestlen=%d"
			,length_data
			,last_version
			,last_hash_length
		));
		// read main data
		byte[] data = new byte[length_data];
		ByteBuffer b = ByteBuffer.wrap(data);
		datafile_channel.position(pagesize);
		int nRead = 0;
		while(nRead<length_data){
			int delta = datafile_channel.read(b);
			if(delta <= 0 ) throw new RuntimeException(String.format("unexpected EOF (read=%d,remain=%d)",nRead,length_data-nRead));
			nRead += delta;
		}
		if( length_data > 0 ){
			// データがカラではない場合はダイジェストを確認する
			byte[] digest = check_digest(data);
			if( digest.length != last_hash_length ) throw new RuntimeException("datafile is broken. digest size not match.");
			for(int i=0;i<last_hash_length;++i){
				if( last_hash[i] != digest[i] ) throw new RuntimeException("datafile is broken. digest not match.");
			}
		}
		last_data = data;
		return data; 
	}
	
	// データのセーブ(内部処理のみで、ロックを含まない)
	private void save_sub(byte[] data) throws IOException{
		int data_length = data.length;
		
		// ダイジェストを計算する
		byte[] digest = check_digest(data);
		
		// バージョン番号を計算する
		int new_version;
		if( last_version == Integer.MAX_VALUE 
		||	last_version <= 0
		){
			new_version = 1;
		}else{
			new_version = last_version +1;
		}

		if(debug) Log.d(TAG,String.format( "save: datalen=%d,version=%d,digestlen=%d"
			,data_length
			,new_version
			,digest.length
		));
		
		// データを書き込む
		{
			datafile_channel.position(pagesize);
			ByteBuffer b = ByteBuffer.wrap(data);
			int nWrite = 0;
			while(nWrite < data_length){
				int delta = datafile_channel.write(b);
				nWrite += delta;
			}
			datafile_channel.truncate(pagesize + data_length);
			datafile_channel.force(true);
		}

		// メタデータを書き込む
		{
			datafile_map.position(0);
			datafile_map.putInt( data_length );
			datafile_map.putInt( new_version );
			datafile_map.putInt( digest.length );
			datafile_map.put( digest );
			datafile_map.force();
		}

		// バックアップファイルに書き込む
		{
			// make metadata bytes
			ByteBuffer c = bb_tmp;
			c.position(0);
			c.putInt( data_length );
			c.putInt( new_version );
			c.putInt( digest.length );
			c.put( digest );
			c.flip();
			// write metadata
			backupfile_channel.position(0);
			while( c.remaining() > 0 ){
				backupfile_channel.write(c);
			}
			// write data
			backupfile_channel.truncate(pagesize + data_length);
			backupfile_channel.position(pagesize);
			ByteBuffer b = ByteBuffer.wrap(data);
			while( b.remaining() > 0 ){
				backupfile_channel.write(b);
			}
			backupfile_channel.force(true);
		}
	}

	///////////////////////////////////////////////////
	// ユーティリティ

	// Androidの非公開APIを使ってファイルパーミッションを設定する
	public static final int set_permission(String path,int perms){
		return set_permission(path,perms,-1,-1);
	}

	// Androidの非公開APIを使ってファイルパーミッションを設定する
	public static final int set_permission(String path,int perms,int uid,int gid){
		try{
			//
			Class<?> clazz = Class.forName("android.os.FileUtils");
			Method method= clazz.getMethod("setPermissions",String.class ,int.class ,int.class ,int.class);
			// 
			return ((Integer)(method.invoke(null,path,perms,uid,gid))).intValue();
			// returns 0 or errno
		}catch(Throwable ex){
			ex.printStackTrace();
			return -1;
		}
	}

	// ダイジェストの計算
	public static final byte[] check_digest(byte[] data){
		try{
			MessageDigest digest_maker = MessageDigest.getInstance(digest_type);
			digest_maker.update(data);
			return digest_maker.digest();
		}catch(NoSuchAlgorithmException ex){
			throw new RuntimeException(ex);
		}
	}
}

