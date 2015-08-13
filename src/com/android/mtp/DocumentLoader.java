/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;

class DocumentLoader {
    static final int NUM_INITIAL_ENTRIES = 10;
    static final int NUM_LOADING_ENTRIES = 20;
    static final int NOTIFY_PERIOD_MS = 500;

    private final MtpManager mMtpManager;
    private final ContentResolver mResolver;
    private final LinkedList<LoaderTask> mTasks = new LinkedList<LoaderTask>();
    private boolean mHasBackgroundThread = false;

    DocumentLoader(MtpManager mtpManager, ContentResolver resolver) {
        mMtpManager = mtpManager;
        mResolver = resolver;
    }

    private static MtpDocument[] loadDocuments(MtpManager manager, int deviceId, int[] handles)
            throws IOException {
        final MtpDocument[] documents = new MtpDocument[handles.length];
        for (int i = 0; i < handles.length; i++) {
            documents[i] = manager.getDocument(deviceId, handles[i]);
        }
        return documents;
    }

    synchronized Cursor queryChildDocuments(String[] columnNames, Identifier parent)
            throws IOException {
        LoaderTask task = findTask(parent);
        if (task == null) {
            int parentHandle = parent.mObjectHandle;
            // Need to pass the special value MtpManager.OBJECT_HANDLE_ROOT_CHILDREN to
            // getObjectHandles if we would like to obtain children under the root.
            if (parentHandle == MtpDocument.DUMMY_HANDLE_FOR_ROOT) {
                parentHandle = MtpManager.OBJECT_HANDLE_ROOT_CHILDREN;
            }
            task = new LoaderTask(parent, mMtpManager.getObjectHandles(
                    parent.mDeviceId, parent.mStorageId, parentHandle));
            task.fillDocuments(loadDocuments(
                    mMtpManager,
                    parent.mDeviceId,
                    task.getUnloadedObjectHandles(NUM_INITIAL_ENTRIES)));
        }

        // Move this task to the head of the list to prioritize it.
        mTasks.remove(task);
        mTasks.addFirst(task);
        if (!task.completed() && !mHasBackgroundThread) {
            mHasBackgroundThread = true;
            new BackgroundLoaderThread().start();
        }

        return task.createCursor(mResolver, columnNames);
    }

    synchronized void clearCache(int deviceId) {
        int i = 0;
        while (i < mTasks.size()) {
            if (mTasks.get(i).mIdentifier.mDeviceId == deviceId) {
                mTasks.remove(i);
            } else {
                i++;
            }
        }
    }

    synchronized void clearCache() {
        int i = 0;
        while (i < mTasks.size()) {
            if (mTasks.get(i).completed()) {
                mTasks.remove(i);
            } else {
                i++;
            }
        }
    }

    private LoaderTask findTask(Identifier parent) {
        for (int i = 0; i < mTasks.size(); i++) {
            if (mTasks.get(i).mIdentifier.equals(parent))
                return mTasks.get(i);
        }
        return null;
    }

    private LoaderTask findUncompletedTask() {
        for (int i = 0; i < mTasks.size(); i++) {
            if (!mTasks.get(i).completed())
                return mTasks.get(i);
        }
        return null;
    }

    private class BackgroundLoaderThread extends Thread {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                LoaderTask task;
                int deviceId;
                int[] handles;
                synchronized (DocumentLoader.this) {
                    task = findUncompletedTask();
                    if (task == null) {
                        mHasBackgroundThread = false;
                        return;
                    }
                    deviceId = task.mIdentifier.mDeviceId;
                    handles = task.getUnloadedObjectHandles(NUM_LOADING_ENTRIES);
                }
                MtpDocument[] documents;
                try {
                    documents = loadDocuments(mMtpManager, deviceId, handles);
                } catch (IOException exception) {
                    documents = null;
                    Log.d(MtpDocumentsProvider.TAG, exception.getMessage());
                }
                synchronized (DocumentLoader.this) {
                    if (documents != null) {
                        task.fillDocuments(documents);
                        final boolean shouldNotify =
                                task.mLastNotified.getTime() <
                                new Date().getTime() - NOTIFY_PERIOD_MS ||
                                task.completed();
                        if (shouldNotify) {
                            task.notify(mResolver);
                        }
                    } else {
                        mTasks.remove(task);
                    }
                }
            }
        }
    }

    private static class LoaderTask {
        final Identifier mIdentifier;
        final int[] mObjectHandles;
        final MtpDocument[] mDocuments;
        Date mLastNotified;
        int mNumLoaded;

        LoaderTask(Identifier identifier, int[] objectHandles) {
            mIdentifier = identifier;
            mObjectHandles = objectHandles;
            mDocuments = new MtpDocument[mObjectHandles.length];
            mNumLoaded = 0;
            mLastNotified = new Date();
        }

        Cursor createCursor(ContentResolver resolver, String[] columnNames) {
            final MatrixCursor cursor = new MatrixCursor(columnNames);
            final Identifier rootIdentifier = new Identifier(
                    mIdentifier.mDeviceId, mIdentifier.mStorageId);
            for (int i = 0; i < mNumLoaded; i++) {
                mDocuments[i].addToCursor(rootIdentifier, cursor.newRow());
            }
            final Bundle extras = new Bundle();
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, !completed());
            cursor.setNotificationUri(resolver, createUri());
            cursor.respond(extras);
            return cursor;
        }

        boolean completed() {
            return mNumLoaded == mDocuments.length;
        }

        int[] getUnloadedObjectHandles(int count) {
            return Arrays.copyOfRange(
                    mObjectHandles,
                    mNumLoaded,
                    Math.min(mNumLoaded + count, mObjectHandles.length));
        }

        void notify(ContentResolver resolver) {
            resolver.notifyChange(createUri(), null, false);
            mLastNotified = new Date();
        }

        void fillDocuments(MtpDocument[] documents) {
            for (int i = 0; i < documents.length; i++) {
                mDocuments[mNumLoaded++] = documents[i];
            }
        }

        private Uri createUri() {
            return DocumentsContract.buildChildDocumentsUri(
                    MtpDocumentsProvider.AUTHORITY, mIdentifier.toDocumentId());
        }
    }
}
