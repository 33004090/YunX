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
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class C139AccountDao_Impl implements C139AccountDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<C139AccountEntity> __insertionAdapterOfC139AccountEntity;

  private final SharedSQLiteStatement __preparedStmtOfClear;

  public C139AccountDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfC139AccountEntity = new EntityInsertionAdapter<C139AccountEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `c139_account` (`id`,`cookie`,`nickname`,`authorization`,`updatedAt`) VALUES (?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final C139AccountEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getCookie());
        statement.bindString(3, entity.getNickname());
        statement.bindString(4, entity.getAuthorization());
        statement.bindLong(5, entity.getUpdatedAt());
      }
    };
    this.__preparedStmtOfClear = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM c139_account WHERE id = 'c139'";
        return _query;
      }
    };
  }

  @Override
  public Object upsert(final C139AccountEntity account,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfC139AccountEntity.insert(account);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object clear(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClear.acquire();
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
          __preparedStmtOfClear.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<C139AccountEntity> observeAccount() {
    final String _sql = "SELECT * FROM c139_account WHERE id = 'c139'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"c139_account"}, new Callable<C139AccountEntity>() {
      @Override
      @Nullable
      public C139AccountEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfCookie = CursorUtil.getColumnIndexOrThrow(_cursor, "cookie");
          final int _cursorIndexOfNickname = CursorUtil.getColumnIndexOrThrow(_cursor, "nickname");
          final int _cursorIndexOfAuthorization = CursorUtil.getColumnIndexOrThrow(_cursor, "authorization");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final C139AccountEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpCookie;
            _tmpCookie = _cursor.getString(_cursorIndexOfCookie);
            final String _tmpNickname;
            _tmpNickname = _cursor.getString(_cursorIndexOfNickname);
            final String _tmpAuthorization;
            _tmpAuthorization = _cursor.getString(_cursorIndexOfAuthorization);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new C139AccountEntity(_tmpId,_tmpCookie,_tmpNickname,_tmpAuthorization,_tmpUpdatedAt);
          } else {
            _result = null;
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
  public Object getAccount(final Continuation<? super C139AccountEntity> $completion) {
    final String _sql = "SELECT * FROM c139_account WHERE id = 'c139'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<C139AccountEntity>() {
      @Override
      @Nullable
      public C139AccountEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfCookie = CursorUtil.getColumnIndexOrThrow(_cursor, "cookie");
          final int _cursorIndexOfNickname = CursorUtil.getColumnIndexOrThrow(_cursor, "nickname");
          final int _cursorIndexOfAuthorization = CursorUtil.getColumnIndexOrThrow(_cursor, "authorization");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final C139AccountEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpCookie;
            _tmpCookie = _cursor.getString(_cursorIndexOfCookie);
            final String _tmpNickname;
            _tmpNickname = _cursor.getString(_cursorIndexOfNickname);
            final String _tmpAuthorization;
            _tmpAuthorization = _cursor.getString(_cursorIndexOfAuthorization);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new C139AccountEntity(_tmpId,_tmpCookie,_tmpNickname,_tmpAuthorization,_tmpUpdatedAt);
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
