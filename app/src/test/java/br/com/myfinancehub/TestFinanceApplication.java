package br.com.myfinancehub;

/** Existing feature suites exercise an already unlocked local session. Auth suites use the real app. */
public final class TestFinanceApplication extends FinanceApplication {
    @Override public void onCreate() {
        super.onCreate();
        AuthSession.unlock();
    }
}
