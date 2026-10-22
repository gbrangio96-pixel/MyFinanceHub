package br.com.myfinancehub;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

/** Every financial activity checks the in-memory session, including returning from the background. */
public abstract class LockedActivity extends Activity {
    private void visibility(int visibility){
        // Android's makeVisible() can show the DecorView after onResume. The content frame
        // must stay hidden too, including when a subclass renders a fresh setContentView.
        getWindow().getDecorView().setVisibility(visibility);
        View content=findViewById(android.R.id.content);if(content!=null)content.setVisibility(visibility);
    }
    @Override protected void onResume(){super.onResume();if(!AuthSession.isUnlocked()){
        visibility(View.INVISIBLE);
        startActivity(new Intent(this,LoginActivity.class).putExtra("return_to_activity",true));
    }else visibility(View.VISIBLE);}
    @Override protected void onPause(){visibility(View.INVISIBLE);super.onPause();}
}
