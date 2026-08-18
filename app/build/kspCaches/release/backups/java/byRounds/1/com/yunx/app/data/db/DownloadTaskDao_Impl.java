package com.yunx.app.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DownloadTaskDao_Impl implements DownloadTaskDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<DownloadTaskEntity> __insertionAdapterOfDownloadTaskEntity;

  private final SharedSQLiteStatement __preparedStmtOfUpdateProgress;

  private final SharedSQLiteStatement __preparedStmtOfUpdateStatus;

  private final SharedSQLiteStatement __preparedStmtOfUpdateError;

  private final SharedSQLiteStatement __preparedStmtOfComplete;

  private final SharedSQLiteStatement __preparedStmtOfDelete;

  public DownloadTaskDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfDownloadTaskEntity = new EntityInsertionAdapter<DownloadTaskEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `download_task` (`id`,`url`,`fileName`,`totalSize`,`downloadedSize`,`status`,`errorMsg`,`savePath`,`createTime`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DownloadTaskEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getUrl());
        statement.bindString(3, entity.getFileName());
        statement.bindLong(4, entity.getTotalSize());
        statement.bindLong(5, entity.getDownloadedSize());
        statement.bindLong(6, entity.getStatus());
        statement.bindString(7, entity.getErrorMsg());
        statement.bindString(8, entity.getSavePath());
        statement.bindLong(9, entity.getCreateTime());
      }
    };
    this.__preparedStmtOfUpdateProgress = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE download_task SET status = ?, downloadedSize = ?, totalSize = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE download_task SET status = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateError = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE download_task SET errorMsg = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfComplete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE download_task SET status = ?, savePath = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM download_task WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final DownloadTaskEntity task,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfDownloadTaskEntity.insertAndReturnId(task);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateProgress(final long id, final int status, final long downloadedSize,
      final long totalSize, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateProgress.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, status);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, downloadedSize);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, totalSize);
        _argIndex = 4;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateProgress.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateStatus(final long id, final int status,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateStatus.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, status);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateError(final long id, final String errorMsg,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateError.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, errorMsg);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateError.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object complete(final long id, final int status, final String savePath,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfComplete.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, status);
        _argIndex = 2;
        _stmt.bindString(_argIndex, savePath);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfComplete.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDelete.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDelete.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<DownloadTaskEntity>> observeAll() {
    final String _sql = "SELECT * FROM download_task ORDER BY createTime DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"download_task"}, new Callable<List<DownloadTaskEntity>>() {
      @Override
      @NonNull
      public List<DownloadTaskEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfTotalSize = CursorUtil.getColumnIndexOrThrow(_cursor, "totalSize");
          final int _cursorIndexOfDownloadedSize = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedSize");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfErrorMsg = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMsg");
          final int _cursorIndexOfSavePath = CursorUtil.getColumnIndexOrThrow(_cursor, "savePath");
          final int _cursorIndexOfCreateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createTime");
          final List<DownloadTaskEntity> _result = new ArrayList<DownloadTaskEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final DownloadTaskEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpUrl;
            _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final long _tmpTotalSize;
            _tmpTotalSize = _cursor.getLong(_cursorIndexOfTotalSize);
            final long _tmpDownloadedSize;
            _tmpDownloadedSize = _cursor.getLong(_cursorIndexOfDownloadedSize);
            final int _tmpStatus;
            _tmpStatus = _cursor.getInt(_cursorIndexOfStatus);
            final String _tmpErrorMsg;
            _tmpErrorMsg = _cursor.getString(_cursorIndexOfErrorMsg);
            final String _tmpSavePath;
            _tmpSavePath = _cursor.getString(_cursorIndexOfSavePath);
            final long _tmpCreateTime;
            _tmpCreateTime = _cursor.getLong(_cursorIndexOfCreateTime);
            _item = new DownloadTaskEntity(_tmpId,_tmpUrl,_tmpFileName,_tmpTotalSize,_tmpDownloadedSize,_tmpStatus,_tmpErrorMsg,_tmpSavePath,_tmpCreateTime);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object get(final long id, final Continuation<? super DownloadTaskEntity> $completion) {
    final String _sql = "SELECT * FROM download_task WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<DownloadTaskEntity>() {
      @Override
      @Nullable
      public DownloadTaskEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfTotalSize = CursorUtil.getColumnIndexOrThrow(_cursor, "totalSize");
          final int _cursorIndexOfDownloadedSize = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedSize");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfErrorMsg = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMsg");
          final int _cursorIndexOfSavePath = CursorUtil.getColumnIndexOrThrow(_cursor, "savePath");
          final int _cursorIndexOfCreateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createTime");
          final DownloadTaskEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpUrl;
            _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final long _tmpTotalSize;
            _tmpTotalSize = _cursor.getLong(_cursorIndexOfTotalSize);
            final long _tmpDownloadedSize;
            _tmpDownloadedSize = _cursor.getLong(_cursorIndexOfDownloadedSize);
            final int _tmpStatus;
            _tmpStatus = _cursor.getInt(_cursorIndexOfStatus);
            final String _tmpErrorMsg;
            _tmpErrorMsg = _cursor.getString(_cursorIndexOfErrorMsg);
            final String _tmpSavePath;
            _tmpSavePath = _cursor.getString(_cursorIndexOfSavePath);
            final long _tmpCreateTime;
            _tmpCreateTime = _cursor.getLong(_cursorIndexOfCreateTime);
            _result = new DownloadTaskEntity(_tmpId,_tmpUrl,_tmpFileName,_tmpTotalSize,_tmpDownloadedSize,_tmpStatus,_tmpErrorMsg,_tmpSavePath,_tmpCreateTime);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
