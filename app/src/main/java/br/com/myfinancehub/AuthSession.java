package br.com.myfinancehub;

/** Authentication state is process memory only. Leaving the app locks the local interface. */
public final class AuthSession {
    private static volatile boolean unlocked;
    private AuthSession(){}
    public static boolean isUnlocked(){return unlocked;}
    public static void unlock(){unlocked=true;}
    public static void lock(){unlocked=false;}
}
