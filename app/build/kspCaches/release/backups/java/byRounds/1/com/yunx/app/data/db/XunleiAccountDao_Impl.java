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
public final class XunleiAccountDao_Impl implements XunleiAccountDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<XunleiAccountEntity> __insertionAdapterOfXunleiAccountEntity;

  private final SharedSQLiteStatement __preparedStmtOfClear;

  public XunleiAccountDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfXunleiAccountEntity = new EntityInsertionAdapter<XunleiAccountEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `xunlei_account` (`id`,`accessToken`,`refreshToken`,`deviceId`,`captchaToken`,`nickname`,`updatedAt`) VALUES (?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final XunleiAccountEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getAccessToken());
        statement.bindString(3, entity.getRefreshToken());
        statement.bindString(4, entity.getDeviceId());
        statement.bindString(5, entity.getCaptchaToken());
        statement.bindString(6, entity.getNickname());
        statement.bindLong(7, entity.getUpdatedAt());
      }
    };
    this.__preparedStmtOfClear = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM xunlei_account WHERE id = 'xunlei'";
        return _query;
      }
    };
  }

  @Override
  public Object upsert(final XunleiAccountEntity account,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfXunleiAccountEntity.insert(account);
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
  public Flow<XunleiAccountEntity> observeAccount() {
    final String _sql = "SELECT * FROM xunlei_account WHERE id = 'xunlei'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"xunlei_account"}, new Callable<XunleiAccountEntity>() {
      @Override
      @Nullable
      public XunleiAccountEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfAccessToken = CursorUtil.getColumnIndexOrThrow(_cursor, "accessToken");
          final int _cursorIndexOfRefreshToken = CursorUtil.getColumnIndexOrThrow(_cursor, "refreshToken");
          final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceId");
          final int _cursorIndexOfCaptchaToken = CursorUtil.getColumnIndexOrThrow(_cursor, "captchaToken");
          final int _cursorIndexOfNickname = CursorUtil.getColumnIndexOrThrow(_cursor, "nickname");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final XunleiAccountEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpAccessToken;
            _tmpAccessToken = _cursor.getString(_cursorIndexOfAccessToken);
            final String _tmpRefreshToken;
            _tmpRefreshToken = _cursor.getString(_cursorIndexOfRefreshToken);
            final String _tmpDeviceId;
            _tmpDeviceId = _cursor.getString(_cursorIndexOfDeviceId);
            final String _tmpCaptchaToken;
            _tmpCaptchaToken = _cursor.getString(_cursorIndexOfCaptchaToken);
            final String _tmpNickname;
            _tmpNickname = _cursor.getString(_cursorIndexOfNickname);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new XunleiAccountEntity(_tmpId,_tmpAccessToken,_tmpRefreshToken,_tmpDeviceId,_tmpCaptchaToken,_tmpNickname,_tmpUpdatedAt);
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
  public Object getAccount(final Continuation<? super XunleiAccountEntity> $completion) {
    final String _sql = "SELECT * FROM xunlei_account WHERE id = 'xunlei'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<XunleiAccountEntity>() {
      @Override
      @Nullable
      public XunleiAccountEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfAccessToken = CursorUtil.getColumnIndexOrThrow(_cursor, "accessToken");
          final int _cursorIndexOfRefreshToken = CursorUtil.getColumnIndexOrThrow(_cursor, "refreshToken");
          final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceId");
          final int _cursorIndexOfCaptchaToken = CursorUtil.getColumnIndexOrThrow(_cursor, "captchaToken");
          final int _cursorIndexOfNickname = CursorUtil.getColumnIndexOrThrow(_cursor, "nickname");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final XunleiAccountEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpAccessToken;
            _tmpAccessToken = _cursor.getString(_cursorIndexOfAccessToken);
            final String _tmpRefreshToken;
            _tmpRefreshToken = _cursor.getString(_cursorIndexOfRefreshToken);
            final String _tmpDeviceId;
            _tmpDeviceId = _cursor.getString(_cursorIndexOfDeviceId);
            final String _tmpCaptchaToken;
            _tmpCaptchaToken = _cursor.getString(_cursorIndexOfCaptchaToken);
            final String _tmpNickname;
            _tmpNickname = _cursor.getString(_cursorIndexOfNickname);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new XunleiAccountEntity(_tmpId,_tmpAccessToken,_tmpRefreshToken,_tmpDeviceId,_tmpCaptchaToken,_tmpNickname,_tmpUpdatedAt);
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
