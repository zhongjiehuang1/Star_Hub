package com.starhub;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Prevents continued delivery after the original session's server deadline. */
final class ResultReadGate {
    @FunctionalInterface interface Check {long remaining()throws Exception;}
    private final Check check;private final BooleanSupplier sameSession;private final LongSupplier clock;
    private long deadline;private StorageFailure denial;
    ResultReadGate(Check check,BooleanSupplier sameSession){this(check,sameSession,System::nanoTime);}
    ResultReadGate(Check check,BooleanSupplier sameSession,LongSupplier clock){this.check=check;this.sameSession=sameSession;this.clock=clock;refresh();}
    boolean refresh(){
        if(denial!=null)return false;
        try{
            if(!sameSession.getAsBoolean())throw ended();
            long before=clock.getAsLong(),remaining=check.remaining();
            if(remaining<=0)throw ended();
            deadline=remaining==Long.MAX_VALUE?Long.MAX_VALUE:before+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining);
            if(deadline!=Long.MAX_VALUE&&clock.getAsLong()-deadline>=0)throw ended();
            if(!sameSession.getAsBoolean())throw ended();return true;
        }catch(StorageFailure denied){denial=denied;return false;}
        catch(Exception unavailable){denial=StorageFailure.unavailable();return false;}
    }
    boolean allow(){if(denial!=null)return false;if(!sameSession.getAsBoolean()){denial=ended();return false;}return deadline==Long.MAX_VALUE||clock.getAsLong()-deadline<0||refresh();}
    StorageFailure denial(){return denial==null?ended():denial;}
    private static StorageFailure ended(){return new StorageFailure(401,"SESSION_ENDED","登录会话已结束，请重新登录原账户后查看原结果。");}
}
