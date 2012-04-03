package jp.juggler.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

public class TestService extends Service{
    static final String TAG="TEST";
	TransactionalFileAccess datafile;
	ConfigurationFileSP pref;
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
        try{
        	TransactionalFileAccess.debug =true;
	        datafile = new TransactionalFileAccess(
	        	getFileStreamPath("ActTestDataFile").getPath(),
	        	0600,
	        	true
	        );
        }catch(IOException ex){
        	ex.printStackTrace();
        	Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();
        }
        try{
        	pref = ConfigurationFileSP.getInstance(getFileStreamPath(ActTestPref.pref_filename).getPath(),false);
        }catch(IOException ex){
        	ex.printStackTrace();
        }
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stop_threads();
		if(datafile!=null) datafile.close();
	}

	// 1.x だとこちらが呼ばれる
	@Override public void onStart(Intent intent, int startId) {
	    handleCommand(intent);
	}
	// 2.x 以降はこちらが呼ばれる
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
	    handleCommand(intent);
	    // We want this service to continue running until it is explicitly
	    // stopped, so return sticky.
	    return START_STICKY;
	}
	
	void handleCommand(Intent intent){
		String action = intent.getAction();
		if(action==null) return;
		
		if( action.equals( getPackageName()+".LockFromService") ){
			stop_threads();
			thread_test1 = new Test1();
			thread_test1.start();
		}else if( action.equals( getPackageName()+".CheckFromService") ){
			stop_threads();
			thread_test2 = new Test2();
			thread_test2.start();
		}else if( action.equals( getPackageName()+".PrefTest") ){
			stop_threads();
			thread_test3 = new Test3();
			thread_test3.start();
		}else if( action.equals( getPackageName()+".StopService") ){
			
			stopSelf();
		}
	}
	
	void stop_threads(){
		if( thread_test1 != null ){
			while( thread_test1.isAlive() ){
				thread_test1.cancel();
				try{ thread_test1.join(333); }catch(InterruptedException ex){}
			}
		}
		if( thread_test2 != null ){
			while( thread_test2.isAlive() ){
				thread_test2.cancel();
				try{ thread_test2.join(333); }catch(InterruptedException ex){}
			}
		}
		if( thread_test3 != null ){
			while( thread_test3.isAlive() ){
				thread_test3.cancel();
				try{ thread_test3.join(333); }catch(InterruptedException ex){}
			}
		}
	}
	
	Test1 thread_test1;
	class Test1 extends Thread{
		AtomicBoolean bCancelled = new AtomicBoolean(false);
		void cancel(){
			bCancelled.set(true);
			synchronized (this) {
				notify();
			}
		}
		public void run(){
			Log.d(TAG,"(Service) thread start.");
			try{
				Log.d(TAG,"(Service) get lock...");
				datafile.lock();
				Log.d(TAG,"(Service) got lock!!");
				try{
					long end = SystemClock.uptimeMillis() +1000 * 3;
					for(;;){
						if( bCancelled.get() ) break;
						long remain = end - SystemClock.uptimeMillis();
						if( remain <= 0 ) break;
						synchronized(this){
							wait(remain);
						}
					}
				}finally{
					datafile.unlock();
					Log.d(TAG,"(Service) lock released.");
				}
			}catch(Throwable ex){
				ex.printStackTrace();
			}
			Log.d(TAG,"(Service) thread end.");
		}
	}
	
	Test2 thread_test2;
	class Test2 extends Thread{
		AtomicBoolean bCancelled = new AtomicBoolean(false);
		void cancel(){
			bCancelled.set(true);
			synchronized (this) {
				notify();
			}
		}
		public void run(){
			Log.d(TAG,"(Service) thread start.");
			try{
				long end = SystemClock.uptimeMillis() +1000 * 10;
				for(;;){
					if( bCancelled.get() ) break;
					//
					byte[] data = datafile.load_if_update();
					if( data != null ) Log.d(TAG,"(Service)get data!");
					//
					if( bCancelled.get() ) break;
					//
					long remain = end - SystemClock.uptimeMillis();
					if( remain <= 0 ) break;
					if( remain > 333 ) remain = 333;
					synchronized(this){
						wait(remain);
					}
				}
			}catch(Throwable ex){
				ex.printStackTrace();
			}
			Log.d(TAG,"(Service) thread end.");
		}
	}
	
	Test3 thread_test3;
	class Test3 extends Thread{
		AtomicBoolean bCancelled = new AtomicBoolean(false);
		void cancel(){
			bCancelled.set(true);
			synchronized (this) {
				notify();
			}
		}
		public void run(){
			Log.d(TAG,"(Service) thread start.");
			try{
				while(!bCancelled.get()){
					String key = "kLong";
					long old = pref.getLong(key,0);
					SharedPreferences.Editor e = pref.edit();
					e.putLong(key,old+1);
					e.commit();
					synchronized(this){ wait(1000); }
				}
			}catch(Throwable ex){
				ex.printStackTrace();
			}
			Log.d(TAG,"(Service) thread end.");
		}
	}
}

