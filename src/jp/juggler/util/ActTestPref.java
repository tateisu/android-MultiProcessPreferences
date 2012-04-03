package jp.juggler.util;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import jp.juggler.TestApp120403.R;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

public class ActTestPref extends Activity {
	Activity self = this;
	ConfigurationFileSP pref;
	TextView tvDump;
	
	static final String pref_filename = "pref_ex";
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act_test_pref);
        
        tvDump =(TextView) findViewById(R.id.tvDump);
        
        try{
        	pref = ConfigurationFileSP.getInstance(getFileStreamPath(pref_filename).getPath(),false);
        }catch(IOException ex){
        	ex.printStackTrace();
        }
        
        dump();

        /////////////////////////////////////
        findViewById(R.id.btnDump).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		dump();
			}
		});
        findViewById(R.id.btnChangeInt).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kInt";
        		int old = pref.getInt(key,0);
        		SharedPreferences.Editor e = pref.edit();
        		e.putInt(key,old+1);
        		e.commit();
        		dump();
			}
		});
        findViewById(R.id.btnChangeLong).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kLong";
        		long old = pref.getLong(key,0);
        		SharedPreferences.Editor e = pref.edit();
        		e.putLong(key,old+1);
        		e.commit();
        		dump();
			}
		});
        
        findViewById(R.id.btnChangeFloat).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kFloat";
        		float old = pref.getFloat(key,0);
        		SharedPreferences.Editor e = pref.edit();
        		e.putFloat(key,old+0.5f);
        		e.commit();
        		dump();
			}
		});
        findViewById(R.id.btnChangeBoolean).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kBoolean";
        		boolean old = pref.getBoolean(key,false);
        		SharedPreferences.Editor e = pref.edit();
        		e.putBoolean(key,!old);
        		e.commit();
        		dump();
			}
		});

        findViewById(R.id.btnChangeString).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kString";
        		String old = pref.getString(key,"defval");
        		SharedPreferences.Editor e = pref.edit();
        		e.putString(key,next_string(old));
        		e.commit();
        		dump();
			}
		});

        if( Build.VERSION.SDK_INT < 11 ) findViewById(R.id.btnChangeStringSet).setEnabled(false);
        findViewById(R.id.btnChangeStringSet).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kStringSet";
        		Set<String> old = pref.getStringSet(key,null);
        		Log.d(TAG,"old="+old);
        		
        		HashSet<String> set = new HashSet<String>();
        		set.add( random_string());
        		set.add( random_string());
        		set.add( random_string());
        		SharedPreferences.Editor e = pref.edit();
        		e.putStringSet(key,set);
        		e.commit();
        		dump();
			}
		});
        
        findViewById(R.id.btnSetNull).setOnClickListener(new OnClickListener() {
        	@Override
			public void onClick(View view) {
        		String key = "kNull";
        		String old = pref.getString(null,null);
        		Log.d(TAG,"old="+old);
        		
        		SharedPreferences.Editor e = pref.edit();
        		e.putString(key,null);
        		e.commit();
        		dump();
			}
		});
        
        findViewById(R.id.btnStartService).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(self,TestService.class);
				intent.setAction(getPackageName()+".PrefTest");
				startService(intent);
			}
		});
        findViewById(R.id.btnStopService).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(self,TestService.class);
				intent.setAction(getPackageName()+".StopService");
				startService(intent);
			}
		});
        
        findViewById(R.id.btnRestoreDatafile).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try{
					TransactionalFileAccess f = pref.getDataFile();
					f.close();
					f.getDataFile().delete();
					f.open();
					pref.reload();
					dump();
				}catch(Throwable ex){
					ex.printStackTrace();
				}
			}
		});
        
        findViewById(R.id.btnResetDatafile).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try{
					Log.d(TAG,"(UI) create datafile..");
					pref.create();
					Log.d(TAG,"(UI) datafile created.");
					dump();
				}catch(Throwable ex){
					ex.printStackTrace();
				}
			}
		});
    }
    
    static final String TAG="TEST";
    void test(Context context){
		HashSet<String> set = new HashSet<String>();
		set.add("k1");
		set.add("k2");
		set.add("k3");

		try{
    		Log.d(TAG,"ctor");
    		
    		String key;
    		
    		// 存在しない項目の確認
    		key = "404"; Log.d(TAG,String.format("%s:%s",key,pref.getInt(key,404)));
    		
    		for(int nLoop=0;nLoop<2;++nLoop){
        		Log.d(TAG,"edit start");
        		SharedPreferences.Editor e = pref.edit();
        		e.putInt("int1",nLoop);
        		e.putLong("long1",nLoop * 100001000010000L);
        		e.putFloat("float1",nLoop * 0.001f);
        		e.putBoolean("bool1",((nLoop%2)==0?false:true));
        		e.putString("string1","loop "+nLoop);
        		if( Build.VERSION.SDK_INT >= 11 ){
        			e.putStringSet("string-set1",set);
        		}

        		Log.d(TAG,"edit commit");
        		e.commit();
        		
        		Log.d(TAG,"re-read data");
        		key = "int1"; Log.d(TAG,String.format("%s:%s",key,pref.getInt(key,404)));
        		key = "long1"; Log.d(TAG,String.format("%s:%s",key,pref.getLong(key,404)));
        		key = "float1"; Log.d(TAG,String.format("%s:%s",key,pref.getFloat(key,404)));
        		key = "bool1"; Log.d(TAG,String.format("%s:%s",key,pref.getBoolean(key,false)));
        		key = "string1"; Log.d(TAG,String.format("%s:%s",key,pref.getString(key,null)));
	    		if( Build.VERSION.SDK_INT >= 11 ){
	    			for(String k : pref.getStringSet("string-set1",null) ){
	    				Log.d(TAG,"set key="+k);
	    			}
	    		}
    		}
    		
    		
    	}catch(Throwable ex){
    		ex.printStackTrace();
    	}
    }

    void dump(){
    	StringBuffer sb = new StringBuffer();
    	Map<String,?> data = pref.getAll();
    	ArrayList<String> key_list = new ArrayList<String>();
    	for(String k : data.keySet() ){
    		key_list.add(k);
    	}
    	Collections.sort(key_list);
    	
    	for( String key  :  key_list ){
    		sb.append(key);
    		sb.append(": ");
    		Object v = data.get(key);
    		if( v== null ){
    			sb.append("(null)");
    		}else{
    			sb.append(v);
    		}
    		sb.append("\n");
    	}
    	tvDump.setText(sb);
    }
    
    Random random = new Random();
    
    String next_string(String old){
    	StringBuffer sb = new StringBuffer();
    	sb.append(old.substring(1));
    	sb.append( (char)('A'+random.nextInt(26)));
    	return sb.toString();
    }
    
    String random_string(){
    	StringBuffer sb = new StringBuffer();
    	for(int i=0;i<6;++i){
    		sb.append( (char)('A'+random.nextInt(26)));
    	}
    	return sb.toString();
    }

}