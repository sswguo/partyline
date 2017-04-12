/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.partyline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.apache.commons.lang.StringUtils.join;

/**
 * Maintain information about threads with active locks on a file, along the current lock-level of the file (which
 * determines what additional operations can be added, once the initial operation is started). This class counts
 * referents that have locked a file, to determine when a file is completely unlocked (and could be re-locked for
 * operations that would have been forbidden previously, like deletion).
 *
 * @see LockLevel for more information about allowable operations for given lock levels
 */
final class LockOwner
{

    private WeakReference<Thread> threadRef;

    private Long threadId;

    private String threadName;

    private final Map<String, String> lockRefs = new LinkedHashMap<>();

    private String path;

    private final LockLevel lockLevel;

    LockOwner( String path, String ownerName, String label, LockLevel lockLevel )
    {
        this.path = path;
        this.lockLevel = lockLevel;

        final Thread t = Thread.currentThread();
        this.threadRef = new WeakReference<>( t );
        this.threadName = t.getName() + "(" + label + ")";
        this.threadId = t.getId();

        increment( ownerName, label );
    }

    boolean isLocked()
    {
        return !lockRefs.isEmpty();
    }

    synchronized boolean lock( String ownerName, String label, LockLevel lockLevel )
    {
        switch ( lockLevel )
        {
            case delete:
            case write:
            {
                return false;
            }
            case read:
            {
                if ( this.lockLevel == LockLevel.delete )
                {
                    return false;
                }

                increment( ownerName, label );
                return true;
            }
            default:
                return false;
        }
    }

    long getThreadId()
    {
        return threadId;
    }

    Thread getThread()
    {
        return threadRef.get();
    }

    @Override
    public String toString()
    {
        return String.format( "LockOwner [%s] of: %s", super.hashCode(), path );
    }

    synchronized CharSequence getLockInfo()
    {
        return new StringBuilder().append( "Lock level: " )
                                  .append( lockLevel )
                                  .append( "\nThread: " )
                                  .append( threadName )
                                  .append( "\nLock Count: " )
                                  .append( lockRefs.size() )
                                  .append( "\nReferences:\n  " )
                                  .append( join( lockRefs.entrySet(), "\n  " ) );
    }

    private synchronized int increment( String ownerName, String label )
    {
        if ( ownerName == null )
        {
            ownerName = Thread.currentThread().getName();
        }

        lockRefs.put( ownerName, label );
        Logger logger = LoggerFactory.getLogger( getClass() );

        int lockCount = lockRefs.size();
        logger.trace( "{} Incremented lock count to: {} with ref: {}", this, lockCount, label );
        return lockCount;
    }

    synchronized boolean unlock( String threadName )
    {
        if ( threadName == null )
        {
            threadName = Thread.currentThread().getName();
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        String ref = null;
        if ( !lockRefs.isEmpty() )
        {
            ref = lockRefs.remove( threadName );
        }

        int lockCount = lockRefs.size();
        logger.trace( "{} Decrementing lock count in: {}, popping ref: {}. New count is: {}\nLock Info:\n{}", threadName, this, ref, lockCount, getLockInfo() );

        if ( lockCount < 1 )
        {
            this.threadId = null;
            this.threadRef.clear();
            this.threadName = null;
            return true;
        }

        return false;
    }

    int getLockCount()
    {
        return lockRefs.size();
    }

    LockLevel getLockLevel()
    {
        return lockLevel;
    }

    synchronized void clearLocks()
    {
        lockRefs.clear();
    }
}
