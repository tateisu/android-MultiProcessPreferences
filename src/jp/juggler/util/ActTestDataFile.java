package jp.juggler.util;


import java.io.IOException;

import jp.juggler.TestApp120403.R;
import jp.juggler.util.TransactionalFileAccess.TransactionProc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

public class ActTestDataFile extends Activity {
	Activity self = this;
	TransactionalFileAccess datafile;
    static final String TAG="TEST";

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if( datafile != null ){
			datafile.close();
		}
	}
	
	static final String datafile_name ="ActTestDataFile";

	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act_test_datafile);
        
        try{
            TransactionalFileAccess.debug = true;
	        datafile = new TransactionalFileAccess(
	        	getFileStreamPath(datafile_name).getPath(),
	        	0600,
	        	true
	        );
        }catch(IOException ex){
        	ex.printStackTrace();
        	Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();
        	finish();
        }
        
        findViewById(R.id.btnLockFromService).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(self,TestService.class);
				intent.setAction(getPackageName()+".LockFromService");
				startService(intent);
			}
		});
        findViewById(R.id.btnCheckFromService).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(self,TestService.class);
				intent.setAction(getPackageName()+".CheckFromService");
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
        findViewById(R.id.btnLockFromUI).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				new Thread(new Runnable() {
					public void run() {
						try{
							Log.d(TAG,"(UI)get lock.. ");
							datafile.lock();
							Log.d(TAG,"(UI)got lock!");
							datafile.unlock();
							Log.d(TAG,"(UI)lock released.");
						}catch(Throwable ex){
							Log.d(TAG,"(UI)cannot get lock.");
							ex.printStackTrace();
						}
						
					}
				}).start();
			}
		});
        findViewById(R.id.btnUpdateFromUI).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				new Thread(new Runnable(){

					@Override
					public void run() {
						try{
							Log.d(TAG,"(UI) start transaction...");
							datafile.transaction(new TransactionProc() {
								@Override
								public byte[] update(byte[] old){
									return Long.toString(System.currentTimeMillis()).getBytes();
								}
							});
							Log.d(TAG,"(UI) transaction complete.");
						}catch(Throwable ex){
							Log.d(TAG,"(UI) transaction failed.");
							ex.printStackTrace();
						}
					}
				}).start();
			}
		});
        
        findViewById(R.id.btnResetDatafile).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				new Thread(new Runnable(){
					@Override
					public void run() {
						try{
							Log.d(TAG,"(UI) create datafile..");
							datafile.create();
							Log.d(TAG,"(UI) datafile created.");
						}catch(Throwable ex){
							ex.printStackTrace();
						}
					}
				}).start();
			}
		});
        
        findViewById(R.id.btnRestoreDatafile).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				new Thread(new Runnable(){
					@Override
					public void run() {
						try{
							datafile.close();
							getFileStreamPath("ActTestDataFile").delete();
							datafile.open();
						}catch(Throwable ex){
							ex.printStackTrace();
						}
					}
				}).start();
			}
		});
    }

}