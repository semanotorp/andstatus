/*
 * Copyright (c) 2016 yvolk (Yuri Volkov), http://yurivolkov.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app.data.checker;

import android.database.Cursor;
import android.support.annotation.NonNull;

import org.andstatus.app.data.DbUtils;
import org.andstatus.app.database.table.ActivityTable;
import org.andstatus.app.database.table.AudienceTable;
import org.andstatus.app.database.table.DownloadTable;
import org.andstatus.app.database.table.FriendshipTable;
import org.andstatus.app.database.table.NoteTable;
import org.andstatus.app.database.table.ActorTable;
import org.andstatus.app.net.social.AActivity;
import org.andstatus.app.net.social.ActivityType;
import org.andstatus.app.net.social.Actor;
import org.andstatus.app.util.MyLog;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * @author yvolk@yurivolkov.com
 */
class MergeActors extends DataChecker {

    @Override
    long fixInternal(boolean countOnly) {
        int changedCount = 0;
        for (AActivity activity : getActorsToMerge()) {
            mergeActor(activity);
            changedCount++;
        }
        return changedCount;
    }

    private Set<AActivity> getActorsToMerge() {
        final String method = "getUsersToMerge";

        Set<AActivity> mergeActivities = new ConcurrentSkipListSet<>();
        String sql = "SELECT " + ActorTable._ID
                + ", " + ActorTable.ORIGIN_ID
                + ", " + ActorTable.ACTOR_OID
                + ", " + ActorTable.WEBFINGER_ID
                + " FROM " + ActorTable.TABLE_NAME
                + " ORDER BY " + ActorTable.ORIGIN_ID
                + ", " + ActorTable.ACTOR_OID
                ;
        Cursor c = null;
        long rowsCount = 0;
        try {
            Actor prev = null;
            c = myContext.getDatabase().rawQuery(sql, null);
            while (c.moveToNext()) {
                rowsCount++;
                Actor actor = Actor.fromOriginAndActorOid(myContext.persistentOrigins().fromId(c.getLong(1)),
                        c.getString(2));
                actor.actorId = c.getLong(0);
                actor.setWebFingerId(c.getString(3));
                if (isTheSameUser(prev, actor)) {
                    AActivity activity = whomToMerge(prev, actor);
                    mergeActivities.add(activity);
                    prev = activity.getActor();
                } else {
                    prev = actor;
                }
            }
        } finally {
            DbUtils.closeSilently(c);
        }

        logger.logProgress(method + " ended, " + rowsCount + " users, " + mergeActivities.size() + " to be merged");
        return mergeActivities;
    }

    private boolean isTheSameUser(Actor prev, Actor actor) {
        if (prev == null || actor == null) {
            return false;
        }
        if (!prev.origin.equals(actor.origin)) {
            return false;
        }
        if (!prev.oid.equals(actor.oid)) {
            return false;
        }
        return true;
    }

    @NonNull
    private AActivity whomToMerge(@NonNull Actor prev, @NonNull Actor actor) {
        AActivity activity = AActivity.from(Actor.EMPTY, ActivityType.UPDATE);
        activity.setObjActor(actor);
        Actor mergeWith = prev;
        if (myContext.persistentAccounts().fromActorId(actor.actorId).isValid()) {
            mergeWith = actor;
            activity.setObjActor(prev);
        }
        activity.setActor(mergeWith);
        return activity;
    }

    private void mergeActor(AActivity activity) {
        Actor actor = activity.getObjActor();
        String logMsg = "Merging " + actor + " with " + activity.getActor();
        logger.logProgress(logMsg);
        // TODO: clean the code!
        updateColumn(logMsg, activity, ActivityTable.TABLE_NAME, ActivityTable.ACTOR_ID, false);
        updateColumn(logMsg, activity, ActivityTable.TABLE_NAME, ActivityTable.USER_ID, false);

        updateColumn(logMsg, activity, NoteTable.TABLE_NAME, NoteTable.AUTHOR_ID, false);
        updateColumn(logMsg, activity, NoteTable.TABLE_NAME, NoteTable.IN_REPLY_TO_ACTOR_ID, false);

        updateColumn(logMsg, activity, AudienceTable.TABLE_NAME, AudienceTable.USER_ID, true);
        deleteRows(logMsg, actor, AudienceTable.TABLE_NAME, AudienceTable.USER_ID);

        deleteRows(logMsg, actor, FriendshipTable.TABLE_NAME, FriendshipTable.ACTOR_ID);
        deleteRows(logMsg, actor, FriendshipTable.TABLE_NAME, FriendshipTable.FRIEND_ID);

        deleteRows(logMsg, actor, DownloadTable.TABLE_NAME, DownloadTable.ACTOR_ID);

        deleteRows(logMsg, actor, ActorTable.TABLE_NAME, ActorTable._ID);
    }

    private void updateColumn(String logMsg, AActivity activity, String table, String column, boolean ignoreError) {
        String sql = "";
        try {
            sql = "UPDATE "
                    + table
                    + " SET "
                    + column + "=" + activity.getActor().actorId
                    + " WHERE "
                    + column + "=" + activity.getObjActor().actorId;
            myContext.getDatabase().execSQL(sql);
        } catch (Exception e) {
            if (!ignoreError) {
                logger.logProgress("Error: " + e.getMessage() + ", SQL:" + sql);
                MyLog.e(this, logMsg + ", SQL:" + sql, e);
            }
        }
    }

    private void deleteRows(String logMsg, Actor actor, String table, String column) {
        String sql = "";
        try {
            sql = "DELETE FROM " + table + " WHERE " + column + "=" + actor.actorId;
            myContext.getDatabase().execSQL(sql);
        } catch (Exception e) {
            logger.logProgress("Error: " + e.getMessage() + ", SQL:" + sql);
            MyLog.e(this, logMsg + ", SQL:" + sql, e);
        }
    }
}
