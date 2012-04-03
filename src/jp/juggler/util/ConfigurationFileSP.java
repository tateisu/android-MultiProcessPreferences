/*
	キーと値のマップを保持するタイプの設定管理もキュールです。
	- インタフェースは SharedPreferences とおおむね互換があります。
	- ファイル更新部分は複数プロセスからの読み書きに対応しています。
	
	ただし制限がいくつかあります。
	- OnSharedPreferenceChangeListener をサポートしてません。実行時にUnsupportedOperationExceptionを出します。
	- SharedPreferences.Editor#apply() をサポートしてません。実行時にUnsupportedOperationExceptionを出します。
	- SharedPreferences.Editor#commit の呼び出しスレッドから直接ファイルアクセスを行います。STRICTモードだと問題があるかもしれません。
	- 継承元インタフェースの制限により、エラー時にIOExceptionではなくRuntimeExceptionを投げる場合があります
*/

package jp.juggler.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jp.juggler.util.TransactionalFileAccess;

import android.content.SharedPreferences;

public class ConfigurationFileSP implements SharedPreferences{
	static final String TAG="ConfigurationFileSP";
	
	///////////////////////////////////////////////////
	// スレッド間でのインスタンスの共用

	private static HashMap<String,ConfigurationFileSP> file_map = new HashMap<String,ConfigurationFileSP>();
	public static ConfigurationFileSP getInstance(String path,boolean other_read) throws IOException{
		synchronized (file_map) {
			ConfigurationFileSP instance = file_map.get(path);
			if( instance == null ) instance = new ConfigurationFileSP(path,other_read);
			return instance;
		}
	}

	//////////////////////////////////////////////////////////
	
	final Encoder encoder = new Encoder();
	final TransactionalFileAccess datafile;
	Map<String,?> mMap = null;

	private ConfigurationFileSP(String path,boolean other_read) throws IOException{
		this.datafile = new TransactionalFileAccess(
				path
				,(other_read ? 0664 : 0660 )
				,true
		);
	}
	
	// ファイルを削除して作成し直す
	/*package access*/ TransactionalFileAccess getDataFile(){
		return datafile;
	}
	
	// ファイルを削除して作成し直す
	public void create() throws IOException{
		datafile.create();
		reload();
	}
	
	// 設定データをバイト配列にまとめてエクスポートする。
	public byte[] exportBytes(){
		return new Encoder().encode_map(getAll());
	}

	// exportBytes()由来のデータを設定データとして読み込む。
	public void importBytes(byte[] data){
		importMap(new Encoder().parse_map(data));
	}
	
	// SharedPreferencesなど外部から提供されたmapのデータをインポートする。
	public void importMap(Map<String,?> src){
		ConfigurationEditorSP e = (ConfigurationEditorSP)edit();
		for(Map.Entry<String,?> entry : src.entrySet() ){
			e.mModified.put(entry.getKey(),entry.getValue());
		}
		e.commit();
	}
	
	///////////////////////////////////////////////////
	// 公開インタフェース
	
	@Override
	public Map<String, ?> getAll() {
		synchronized(this){
			check_update();
			return new HashMap<String, Object>(mMap);
		}
	}

	@Override
	public boolean contains(String key)  {
		synchronized(this){
			check_update();
            return mMap.containsKey(key);
		}
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		synchronized(this){
			check_update();
            Boolean v = (Boolean)mMap.get(key);
            return v != null ? v : defValue;
		}
	}

	@Override
	public float getFloat(String key, float defValue) {
		synchronized(this){
			check_update();
            Float v = (Float)mMap.get(key);
            return v != null ? v : defValue;
		}
	}

	@Override
	public int getInt(String key, int defValue) {
		synchronized(this){
			check_update();
            Integer v = (Integer)mMap.get(key);
            return v != null ? v : defValue;

		}
	}

	@Override
	public long getLong(String key, long defValue) {
		synchronized(this){
			check_update();
            Long v = (Long)mMap.get(key);
            return v != null ? v : defValue;
		}
	}

	@Override
	public String getString(String key, String defValue) {
		synchronized(this){
			check_update();
            String v = (String)mMap.get(key);
            return v != null ? v : defValue;

		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Set<String> getStringSet(String key, Set<String> defValues) {
		synchronized(this){
			check_update();
	        Set<String> v = (Set<String>) mMap.get(key);
	        return v != null ? v : defValues;
		}
	}

	/////////////////////////////////////////////////////
	// リスナの管理
	
	@Override
	public void registerOnSharedPreferenceChangeListener( OnSharedPreferenceChangeListener listener){
		throw new UnsupportedOperationException("registerOnSharedPreferenceChangeListener is not supported");
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener( OnSharedPreferenceChangeListener listener){
		throw new UnsupportedOperationException("unregisterOnSharedPreferenceChangeListener is not supported");
	}

	///////////////////////////////////////////////////////////////
	
	@Override
	public Editor edit() {
		return new ConfigurationEditorSP();
	}

    public final class ConfigurationEditorSP implements SharedPreferences.Editor {
    	public Editor putString(String key, String value) {
    		synchronized (this) {
    			mModified.put(key, value);
    			return this;
    		}
    	}
    	public Editor putStringSet(String key, Set<String> values) {
   	          synchronized (this) {
   	        	  mModified.put(key, values);
   	        	  return this;
   	          }
    	}
    	public Editor putInt(String key, int value) {
    		synchronized (this) {
    			mModified.put(key, value);
    			return this;
    		}
    	}
    	public Editor putLong(String key, long value) {
    		synchronized (this) {
    			mModified.put(key, value);
    			return this;
    		}
    	}
    	public Editor putFloat(String key, float value) {
    		synchronized (this) {
    			mModified.put(key, value);
    			return this;
    		}
    	}
    	public Editor putBoolean(String key, boolean value) {
    		synchronized (this) {
    			mModified.put(key, value);
    			return this;
    		}
    	}
    	
        public Editor remove(String key) {
            synchronized (this) {
            	// 「削除された」を示す値はこのEditor自身
                mModified.put(key, this);
                return this;
            }
        }
        
        public Editor clear() {
            synchronized (this) {
                mClear = true;
                return this;
            }
        }

        /////////////////////////////////////////////////////////
         
        public boolean mClear = false;
    	public HashMap<String, Object> mModified = new HashMap<String,Object>();

		@Override
		public void apply() {
			save_background(this);
		}

		@Override
		public boolean commit() {
			return save_foreground(this);
		}
    }
    
    //////////////////////////////////////////////////////////////////////

	public void reload() {
		try{
			synchronized(this){
				mMap = encoder.parse_map(datafile.load());
			}
		}catch(IOException ex){
			throw new RuntimeException(ex);
		}
	}
	
	void check_update(){
		try{
			synchronized(this){
				if( mMap == null ){
					mMap = encoder.parse_map(datafile.load());
				}else{
					byte[] data = datafile.load_if_update();
					if( data != null ) mMap = encoder.parse_map(data);
				}
			}
		}catch(IOException ex){
			throw new RuntimeException(ex);
		}
	}
	
	void save_background(final ConfigurationEditorSP cset){
		throw new UnsupportedOperationException("background update is not supported");
	}
	
	boolean save_foreground(final ConfigurationEditorSP cset){
		try{
			synchronized (this) {
				datafile.transaction(new TransactionalFileAccess.TransactionProc() {
					@Override
					public byte[] update(byte[] old_data){
						HashMap<String,Object> map_new=null;
						if( old_data == null || cset.mClear ){
							map_new = new HashMap<String, Object>();
						}else{
							map_new = encoder.parse_map(old_data);
						}
						for( Map.Entry<String,Object> entry : cset.mModified.entrySet() ){
							String key = entry.getKey();
							Object value = entry.getValue();
							if( value == cset ){
								map_new.remove(key);
							}else{
								map_new.put(key,value);
							}
						}
						return encoder.encode_map(map_new);
					}
				});
				check_update();
			}
			return true;
		}catch(IOException ex){
			throw new RuntimeException(ex);
		}
	}
	
	////////////////////////////////////////////////////////////
	// マップとバイト配列のエンコード/デコード
	
	public static final class Encoder{
		public static final int tmp_size = 1000;
		public static final String UTF8="UTF-8";
		
		private byte[] tmp = new byte[tmp_size];
		private ByteBuffer tmp_bb = ByteBuffer.wrap(tmp);
		
		private final String parse_string(ByteBuffer bb ){
			try{
				int bytesize = bb.getInt();
				if( bytesize <= tmp_size ){
					bb.get(tmp,0,bytesize);
					return new String(tmp,0,bytesize,UTF8);
				}else{
					byte[] b = new byte[bytesize];
					bb.get(b,0,bytesize);
					return new String(b,0,bytesize,UTF8);
				}
			}catch(UnsupportedEncodingException ex){
				throw new RuntimeException(ex);
				// 発生しない
			}
		}
		private final void endoce_string(ByteArrayOutputStream bao, String key){
			try{
				byte[] data = key.getBytes(UTF8);
				encode_int(bao,data.length);
				bao.write(data);
			}catch(IOException ex){
				throw new RuntimeException(ex);
				// 発生しない
			}
		}
		private final Set<String> parse_string_set(ByteBuffer bb ){
			HashSet<String> set = new HashSet<String>();
			int count = bb.getInt();
			while(count-- > 0) set.add(parse_string(bb));
			return set;
		}
		private final void endoce_string_set(ByteArrayOutputStream bao, Set<String> set){
			int count = set.size();
			encode_int(bao,count);
			for( String s : set ){
				endoce_string(bao,s);
			}
		}
		private final void encode_int(ByteArrayOutputStream bao, int value) {
			tmp_bb.clear(); tmp_bb.putInt(value); bao.write(tmp,0,tmp_bb.position());
		}

		private final void encode_long(ByteArrayOutputStream bao, long value) {
			tmp_bb.clear(); tmp_bb.putLong(value); bao.write(tmp,0,tmp_bb.position());
		}
		
		private final void encode_float(ByteArrayOutputStream bao, float value) {
			tmp_bb.clear(); tmp_bb.putFloat(value); bao.write(tmp,0,tmp_bb.position());
		}

		// parse data from ByteBuffer
		public final HashMap<String,Object> parse_map(byte[] data){
			ByteBuffer bb = ByteBuffer.wrap(data);
			bb.position(0);
			//
			HashMap<String,Object> map = new HashMap<String,Object>();
			while( bb.remaining() > 0 ){
				int t =  bb.getInt();
				if( t < 0 || t > 7 ) break;
				String key = parse_string(bb);
				switch(t){
				case 0: map.put(key,null); break;
				case 1: map.put(key,true); break;
				case 2: map.put(key,false); break;
				case 3: map.put(key,bb.getInt()); break;
				case 4: map.put(key,bb.getLong()); break;
				case 5: map.put(key,bb.getFloat()); break;
				case 6: map.put(key,parse_string(bb)); break;
				case 7: map.put(key,parse_string_set(bb)); break;
				}
			}
			return map;
		}
		
		// encode map to bytes
		@SuppressWarnings("unchecked")
		public final byte[] encode_map(Map<String,?> map){
			ByteArrayOutputStream bao = new ByteArrayOutputStream();
			for( Map.Entry<String,?> entry : map.entrySet() ){
				String key = entry.getKey();
				Object value = entry.getValue();
				if( value == null ){
					encode_int( bao,0 );
					endoce_string(bao,key);
				}else if( value instanceof Boolean ){
					if( (Boolean)value ){
						encode_int( bao,1 );
						endoce_string(bao,key);
					}else{
						encode_int( bao,2 );
						endoce_string(bao,key);
					}
				}else if( value instanceof Integer ){
					encode_int( bao,3 );
					endoce_string(bao,key);
					encode_int( bao, (Integer)value );
				}else if( value instanceof Long ){
					encode_int( bao,4 );
					endoce_string(bao,key);
					encode_long( bao, (Long)value );
				}else if( value instanceof Float ){
					encode_int( bao,5 );
					endoce_string(bao,key);
					encode_float( bao, (Float)value );
				}else if( value instanceof String ){
					encode_int( bao,6 );
					endoce_string(bao,key);
					endoce_string( bao, (String)value );
				}else if( value instanceof Set<?> ){
					encode_int( bao,7 );
					endoce_string(bao,key);
					endoce_string_set( bao, (Set<String>)value );
				}else{
					throw new RuntimeException("unsupported data type:"+value.getClass().getName());
				}
			}
			encode_int( bao,-1 ); // end marker
			return bao.toByteArray();
		}
	}


}


