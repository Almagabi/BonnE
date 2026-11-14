package ai.bonne.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class FixtureActivity extends Activity {
    private LinearLayout root;
    private int current=0;
    private static final int ROW_ID=123456789;
    private static final int MARK_ID=234567890;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        showResults();
    }
    private Button button(String label,int id,Runnable act) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(18);
        b.setId(id);
        b.setOnClickListener(v->act.run());
        return b;
    }
    private void showResults() {
        current=0;
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,18,18,18);
        scroll.addView(root);
        setContentView(scroll);
        TextView title=new TextView(this);title.setText("Search Results");title.setTextSize(27);
        root.addView(title);
        for(int i=1;i<=8;i++){
            final int index=i;
            root.addView(button("Profile "+index,ROW_ID,()->showProfile(index)),
                new LinearLayout.LayoutParams(-1,132));
        }
    }
    private void showProfile(int i) {
        current=i;
        getSharedPreferences("fixture_state",MODE_PRIVATE).edit().putInt("opened",i).commit();
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(25,70,25,25);
        setContentView(layout);
        TextView title=new TextView(this);title.setText("Profile "+i);title.setTextSize(30);
        layout.addView(title);
        layout.addView(button("Mark Profile",MARK_ID,()->{
            getSharedPreferences("fixture_marks",MODE_PRIVATE).edit()
                .putInt("profile_"+i,getSharedPreferences("fixture_marks",MODE_PRIVATE)
                    .getInt("profile_"+i,0)+1).commit();
            TextView message=new TextView(this);
            message.setText("Marked profile "+i);
            message.setTextSize(20);
            layout.addView(message);
        }),new LinearLayout.LayoutParams(-1,140));
    }
    @Override public void onBackPressed(){
        if(current>0)showResults();
        else super.onBackPressed();
    }
}
