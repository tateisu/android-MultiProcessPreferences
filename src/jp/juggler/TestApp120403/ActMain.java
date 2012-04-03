package jp.juggler.TestApp120403;

import jp.juggler.util.ActTestDataFile;
import jp.juggler.util.ActTestPref;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

public class ActMain extends Activity {
	Activity self = this;

	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        findViewById(R.id.btnTestDatafile).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(self,ActTestDataFile.class));
			}
		});
        findViewById(R.id.btnTestPref).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(self,ActTestPref.class));
			}
		});
    }
    

}