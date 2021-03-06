package org.commonjava.util.partyline.lock.global.impl;

import org.commonjava.util.partyline.PartylineException;
import org.commonjava.util.partyline.lock.LockLevel;
import org.commonjava.util.partyline.lock.global.GlobalLockManager;
import org.commonjava.util.partyline.lock.global.GlobalLockOwner;
import org.infinispan.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.sleep;

public class InfinispanTransactionalGLM
                implements GlobalLockManager
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final static long DEFAULT_EXPIRATION_IN_MINUTES = 30;

    private final static long DEFAULT_TIMEOUT_IN_MILLIS = TimeUnit.MINUTES.toMillis( 5 ); // 5 minutes

    private final Cache<String, GlobalLockOwner> lockCache;

    private final String id;

    /**
     * The lockCache must be a distributed and transactional cache being accessed by all nodes.
     * @param lockCache
     */
    public InfinispanTransactionalGLM( Cache<String, GlobalLockOwner> lockCache )
    {
        this.lockCache = lockCache;
        String id;
        try
        {
            id = InetAddress.getLocalHost().getHostName(); // pod name if on Openshift
        }
        catch ( UnknownHostException e )
        {
            logger.warn( "Get hostname failed, fall back to UUID", e );
            id = UUID.randomUUID().toString();
        }
        this.id = id;
    }

    @Override
    public boolean tryLock( String path, LockLevel level, long timeoutInMillis ) throws PartylineException
    {
        long cur = currentTimeMillis();
        if ( timeoutInMillis <= 0 )
        {
            timeoutInMillis = DEFAULT_TIMEOUT_IN_MILLIS;
        }

        long end = cur + timeoutInMillis;

        TransactionManager txManager = lockCache.getAdvancedCache().getTransactionManager();

        while ( cur < end )
        {
            Boolean locked = executeInTransaction( txManager, () -> {
                GlobalLockOwner lock = lockCache.get( path );
                logger.debug( "Get global lock owner: {}", lock );

                if ( lock == null )
                {
                    putWithExpiration( path, new GlobalLockOwner( level ).withOwner( this.id ) );
                    return true;
                }

                // if some node is reading and this node want to read too, add this to owner list
                if ( lock.getLevel() == LockLevel.read && level == LockLevel.read )
                {
                    logger.debug( "Add this node {} to owners", this.id );
                    if ( !lock.containsOwner( this.id ) )
                    {
                        lock.withOwner( this.id );
                        putWithExpiration( path, lock );
                    }
                    return true;
                }
                return false;
            } );

            if ( locked == null || !locked )
            {
                sleepQuietly( 1000 );
            }
            else
            {
                return locked;
            }
            cur = currentTimeMillis();
        }
        return false;
    }

    @Override
    public void unlock( String path, LockLevel level ) throws PartylineException
    {
        if ( level == LockLevel.write )
        {
            lockCache.remove( path );
            return;
        }

        TransactionManager txManager = lockCache.getAdvancedCache().getTransactionManager();
        Boolean ret = executeInTransaction( txManager, () -> {
            GlobalLockOwner lock = lockCache.get( path );
            if ( lock == null )
            {
                return true;
            }
            if ( lock.containsOwner( this.id ) )
            {
                lock.removeOwner( this.id );
                if ( lock.isEmpty() )
                {
                    lockCache.remove( path );
                }
                else
                {
                    putWithExpiration( path, lock );
                }
            }
            return true;
        } );
    }

    private void putWithExpiration( String path, GlobalLockOwner lock )
    {
        lockCache.put( path, lock, DEFAULT_EXPIRATION_IN_MINUTES, TimeUnit.MINUTES );
    }

    private void sleepQuietly( long milliseconds )
    {
        try
        {
            sleep( milliseconds );
        }
        catch ( InterruptedException e )
        {
            logger.trace( "Sleep interrupted: {}", e );
        }
    }

    /**
     * If transaction not supported, we throw PartylineException to inform the caller to not try again. For other
     * transactional exceptions, we return null to let caller retry later.
     * @param txManager
     * @param operation
     * @param <T>
     * @return
     * @throws PartylineException if transaction not supported
     */
    private <T> T executeInTransaction( TransactionManager txManager, TransactionalOperation<T> operation )
                    throws PartylineException
    {
        try
        {
            txManager.begin();
        }
        catch ( NotSupportedException e )
        {
            throw new PartylineException( "Transaction not supported", e );
        }
        catch ( SystemException e )
        {
            logger.error( "Failed to begin transaction.", e );
            return null;
        }

        try
        {
            T ret = operation.execute();
            txManager.commit();
            return ret;
        }
        catch ( SystemException | RollbackException | HeuristicMixedException | HeuristicRollbackException e )
        {
            logger.warn( "Failed to commit transaction.", e );
            return null;
        }
        catch ( Exception e )
        {
            try
            {
                logger.error( "Failed to execute. Rolling back.", e );
                txManager.rollback();
            }
            catch ( SystemException e1 )
            {
                logger.error( "Exception during rollback", e1 );
            }
        }
        return null;
    }

    @FunctionalInterface
    interface TransactionalOperation<T>
    {
        T execute() throws Exception;
    }

}
