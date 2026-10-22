package br.com.myfinancehub;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** Local access protection. No password or recovery code is sent to an online service. */
public final class LoginActivity extends Activity {
    private LocalAuthStore auth;private AppAppearance.Palette colors;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private boolean destroyed,busy,resumed;private Runnable pendingCompletion;
    private LinearLayout content;private Button submit,biometric;private EditText username,password,confirm;
    @Override public void onCreate(Bundle state){setTheme(AppAppearance.themeRes(this));super.onCreate(state);colors=AppAppearance.palette(this);auth=new LocalAuthStore(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);if(auth.configured()&&AuthSession.isUnlocked()){enter();return;}render();}
    private void render(){
        if(destroyed||isFinishing())return;
        boolean setup=!auth.configured();LinearLayout root=column();root.setBackgroundColor(colors.background);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});}else root.setFitsSystemWindows(true);
        ScrollView scroll=new ScrollView(this);content=column();content.setPadding(dp(24),dp(32),dp(24),dp(32));scroll.addView(content);root.addView(scroll);setContentView(root);
        label(content,"myfinancehub",28);label(content,setup?"Crie seu acesso local":"Entre no seu aplicativo",23);label(content,"Seus dados ficam neste aparelho. Sem assinatura de serviço, conta online ou recuperação por e-mail.",14);
        username=field(content,"Login",auth.username(),false);password=field(content,"Senha","",true);confirm=setup?field(content,"Confirmar senha","",true):null;
        label(content,"A senha precisa ter no mínimo 8 caracteres, com letra maiúscula, minúscula, número e caractere especial.",13);
        submit=button(content,setup?"Criar acesso local":"Entrar");submit.setOnClickListener(v->{
            if(busy||destroyed||isFinishing())return;long attempt=AuthSession.generation();String login=username.getText().toString();char[] pass=password.getText().toString().toCharArray();char[] again=confirm==null?null:confirm.getText().toString().toCharArray();password.setText("");if(confirm!=null)confirm.setText("");
            if(setup){if(!Arrays.equals(pass,again)){Arrays.fill(pass,'\0');if(again!=null)Arrays.fill(again,'\0');message("As senhas não são iguais.");return;}Arrays.fill(again,'\0');run(()->{try{return auth.create(login,pass);}finally{Arrays.fill(pass,'\0');}},code->{if(AuthSession.unlockIfCurrent(attempt))showRecoveryCode(code,()->offerBiometrics());});}
            else run(()->{try{return auth.verify(login,pass);}finally{Arrays.fill(pass,'\0');}},valid->{if(valid){if(AuthSession.unlockIfCurrent(attempt))offerBiometrics();}else message("Login ou senha incorretos.");});
        });
        biometric=button(content,"Entrar com biometria");biometric.setEnabled(!setup&&LocalBiometrics.enabled(this)&&LocalBiometrics.available(this));biometric.setOnClickListener(v->{if(busy||destroyed||isFinishing())return;long attempt=AuthSession.generation();LocalBiometrics.unlock(this,new LocalBiometrics.Callback(){public void success(){if(!destroyed&&!isFinishing()&&auth.configured()&&AuthSession.unlockIfCurrent(attempt))enter();}public void failure(String error){if(!destroyed&&!isFinishing())message(error);}});});
        if(!setup){button(content,"Recuperar acesso com meu código").setOnClickListener(v->recover());}
        label(content,"Na criação, guarde o código de recuperação fora deste aparelho. Ele permite trocar a senha sem apagar os dados. Sem senha, biometria ou código, não há recuperação automática.",13);
    }
    private void offerBiometrics(){
        if(destroyed||isFinishing()||!AuthSession.isUnlocked())return;
        if(LocalBiometrics.available(this)&&!LocalBiometrics.enabled(this))new AlertDialog.Builder(this).setTitle("Usar biometria para entrar?").setMessage("A biometria cadastrada no Android poderá desbloquear este app. Você continuará podendo usar sua senha.").setNegativeButton("Agora não",(d,w)->enter()).setPositiveButton("Ativar",(d,w)->LocalBiometrics.enable(this,new LocalBiometrics.Callback(){public void success(){if(!destroyed&&!isFinishing())enter();}public void failure(String error){if(!destroyed&&!isFinishing()){message(error);}}})).setOnCancelListener(d->enter()).show();
        else enter();
    }
    private void enter(){if(destroyed||isFinishing()||!AuthSession.isUnlocked())return;
        boolean returning=getIntent().getBooleanExtra("return_to_activity",false);
        if(new PersonalPluggySettings(this).consultOnEntry()){
            Intent query=new Intent(this,PluggyActivity.class).putExtra("consult_on_entry",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if(returning)startActivity(query);else startActivities(new Intent[]{new Intent(this,MainActivity.class),query});
            finish();
        }else if(returning)finish();else{startActivity(new Intent(this,MainActivity.class));finish();}
    }
    private void recover(){
        if(busy||destroyed||isFinishing())return;LinearLayout form=column();form.setPadding(dp(24),dp(12),dp(24),dp(16));EditText login=field(form,"Login",auth.username(),false),code=field(form,"Código de recuperação","",true),next=field(form,"Nova senha","",true),again=field(form,"Confirmar nova senha","",true);ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Recuperar acesso local").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Trocar senha",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{if(busy||destroyed||isFinishing())return;char[] pass=next.getText().toString().toCharArray(),repeat=again.getText().toString().toCharArray();boolean matches=Arrays.equals(pass,repeat);Arrays.fill(repeat,'\0');if(!matches){Arrays.fill(pass,'\0');message("As senhas não são iguais.");return;}String name=login.getText().toString(),recovery=code.getText().toString();next.setText("");again.setText("");code.setText("");run(()->{try{String newCode=auth.resetPassword(name,recovery,pass);LocalBiometrics.disable(getApplicationContext());return newCode;}finally{Arrays.fill(pass,'\0');}},newCode->{AuthSession.lock();dialog.dismiss();showRecoveryCode(newCode,this::render);});}));dialog.show();
    }
    private void showRecoveryCode(String code,Runnable after){
        if(destroyed||isFinishing())return;long codeGeneration=AuthSession.generation();
        LinearLayout body=column();body.setPadding(dp(24),dp(12),dp(24),dp(12));label(body,"Guarde este código em um gerenciador de senhas ou em outro local seguro. Ele substitui a recuperação por e-mail e será mostrado somente agora.",14);TextView value=label(body,code,19);value.setTextIsSelectable(true);
        button(body,"Copiar código").setOnClickListener(v->{if(destroyed||isFinishing()||codeGeneration!=AuthSession.generation())return;ClipData clip=ClipData.newPlainText("Código de recuperação MyFinanceHub",code);PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);});
        new AlertDialog.Builder(this).setTitle("Guarde seu código de recuperação").setView(body).setCancelable(false).setPositiveButton("Guardei o código",(d,w)->after.run()).show();
    }
    interface Job<T>{T run()throws Exception;}interface Done<T>{void accept(T value);}
    private <T>void run(Job<T> job,Done<T> done){
        long attempt=AuthSession.generation();busy=true;submit.setEnabled(false);biometric.setEnabled(false);
        worker.submit(()->{try{T result=job.run();runOnUiThread(()->complete(()->{
            if(!finishAttempt(attempt))return;done.accept(result);
        }));}catch(Exception ex){runOnUiThread(()->complete(()->{if(finishAttempt(attempt))message(ex.getMessage());}));}});
    }
    private boolean finishAttempt(long attempt){
        if(destroyed||isFinishing())return false;
        busy=false;submit.setEnabled(true);biometric.setEnabled(auth.configured()&&LocalBiometrics.enabled(this)&&LocalBiometrics.available(this));
        // A result queued before a device/manual lock must never authorize a later session.
        return attempt==AuthSession.generation();
    }
    private void message(String message){if(destroyed||isFinishing())return;new AlertDialog.Builder(this).setTitle("Acesso local").setMessage(message==null?"Não foi possível concluir. Tente novamente.":message).setPositiveButton("OK",null).show();}
    private void complete(Runnable action){if(destroyed||isFinishing())return;if(resumed)action.run();else pendingCompletion=action;}
    @Override protected void onResume(){super.onResume();if(destroyed||isFinishing())return;resumed=true;if(pendingCompletion!=null){Runnable action=pendingCompletion;pendingCompletion=null;action.run();}}
    @Override protected void onPause(){resumed=false;LocalBiometrics.cancelPending();if(password!=null)password.setText("");if(confirm!=null)confirm.setText("");super.onPause();}
    @Override protected void onDestroy(){destroyed=true;LocalBiometrics.cancelPending();worker.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){AuthSession.lock();finishAffinity();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView label(LinearLayout parent,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(colors.text);v.setPadding(0,dp(8),0,dp(8));parent.addView(v);return v;}
    private EditText field(LinearLayout parent,String hint,String value,boolean secret){label(parent,hint,13);EditText v=new EditText(this);v.setHint(hint);v.setContentDescription(hint);v.setText(value);v.setTextColor(colors.text);v.setHintTextColor(colors.muted);v.setSingleLine(true);v.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_NORMAL));v.setSaveEnabled(false);v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);v.setFilterTouchesWhenObscured(true);v.setMinHeight(dp(48));parent.addView(v);return v;}
    private Button button(LinearLayout parent,String value){Button b=new Button(this);b.setText(value);b.setContentDescription(value);b.setAllCaps(false);b.setMinHeight(dp(48));parent.addView(b);return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
