package com.yunx.app.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile QuarkAccountDao _quarkAccountDao;

  private volatile DownloadTaskDao _downloadTaskDao;

  private volatile UCAccountDao _uCAccountDao;

  private volatile XunleiAccountDao _xunleiAccountDao;

  private volatile BaiduAccountDao _baiduAccountDao;

  private volatile C139AccountDao _c139AccountDao;

  private volatile Pan123AccountDao _pan123AccountDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(9) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `quark_account` (`id` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `download_task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `fileName` TEXT NOT NULL, `totalSize` INTEGER NOT NULL, `downloadedSize` INTEGER NOT NULL, `status` INTEGER NOT NULL, `errorMsg` TEXT NOT NULL, `savePath` TEXT NOT NULL, `createTime` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `uc_account` (`id` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `xunlei_account` (`id` TEXT NOT NULL, `accessToken` TEXT NOT NULL, `refreshToken` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `captchaToken` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `baidu_account` (`id` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `c139_account` (`id` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nickname` TEXT NOT NULL, `authorization` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `pan123_account` (`id` TEXT NOT NULL, `accessToken` TEXT NOT NULL, `account` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e0b24dccd2684771e52f3ccf8eda66a4')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `quark_account`");
        db.execSQL("DROP TABLE IF EXISTS `download_task`");
        db.execSQL("DROP TABLE IF EXISTS `uc_account`");
        db.execSQL("DROP TABLE IF EXISTS `xunlei_account`");
        db.execSQL("DROP TABLE IF EXISTS `baidu_account`");
        db.execSQL("DROP TABLE IF EXISTS `c139_account`");
        db.execSQL("DROP TABLE IF EXISTS `pan123_account`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsQuarkAccount = new HashMap<String, TableInfo.Column>(4);
        _columnsQuarkAccount.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsQuarkAccount.put("cookie", new TableInfo.Column("cookie", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsQuarkAccount.put("nickname", new TableInfo.Column("nickname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsQuarkAccount.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysQuarkAccount = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesQuarkAccount = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoQuarkAccount = new TableInfo("quark_account", _columnsQuarkAccount, _foreignKeysQuarkAccount, _indicesQuarkAccount);
        final TableInfo _existingQuarkAccount = TableInfo.read(db, "quark_account");
        if (!_infoQuarkAccount.equals(_existingQuarkAccount)) {
          return new RoomOpenHelper.ValidationResult(false, "quark_account(com.yunx.app.data.db.QuarkAccountEntity).\n"
                  + " Expected:\n" + _infoQuarkAccount + "\n"
                  + " Found:\n" + _existingQuarkAccount);
        }
        final HashMap<String, TableInfo.Column> _columnsDownloadTask = new HashMap<String, TableInfo.Column>(9);
        _columnsDownloadTask.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("url", new TableInfo.Column("url", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("fileName", new TableInfo.Column("fileName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("totalSize", new TableInfo.Column("totalSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("downloadedSize", new TableInfo.Column("downloadedSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("status", new TableInfo.Column("status", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("errorMsg", new TableInfo.Column("errorMsg", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("savePath", new TableInfo.Column("savePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDownloadTask.put("createTime", new TableInfo.Column("createTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysDownloadTask = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesDownloadTask = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoDownloadTask = new TableInfo("download_task", _columnsDownloadTask, _foreignKeysDownloadTask, _indicesDownloadTask);
        final TableInfo _existingDownloadTask = TableInfo.read(db, "download_task");
        if (!_infoDownloadTask.equals(_existingDownloadTask)) {
          return new RoomOpenHelper.ValidationResult(false, "download_task(com.yunx.app.data.db.DownloadTaskEntity).\n"
                  + " Expected:\n" + _infoDownloadTask + "\n"
                  + " Found:\n" + _existingDownloadTask);
        }
        final HashMap<String, TableInfo.Column> _columnsUcAccount = new HashMap<String, TableInfo.Column>(4);
        _columnsUcAccount.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsUcAccount.put("cookie", new TableInfo.Column("cookie", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsUcAccount.put("nickname", new TableInfo.Column("nickname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsUcAccount.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysUcAccount = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesUcAccount = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoUcAccount = new TableInfo("uc_account", _columnsUcAccount, _foreignKeysUcAccount, _indicesUcAccount);
        final TableInfo _existingUcAccount = TableInfo.read(db, "uc_account");
        if (!_infoUcAccount.equals(_existingUcAccount)) {
          return new RoomOpenHelper.ValidationResult(false, "uc_account(com.yunx.app.data.db.UCAccountEntity).\n"
                  + " Expected:\n" + _infoUcAccount + "\n"
                  + " Found:\n" + _existingUcAccount);
        }
        final HashMap<String, TableInfo.Column> _columnsXunleiAccount = new HashMap<String, TableInfo.Column>(7);
        _columnsXunleiAccount.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsXunleiAccount.put("accessToken", new TableInfo.Column("accessToken", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsXunleiAccount.put("refreshToken", new TableInfo.Column("refreshToken", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsXunleiAccount.put("deviceId", new TableInfo.Column("deviceId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsXunleiAccount.put("captchaToken", new TableInfo.Column("captchaToken", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsXunleiAccount.put("nickname", new TableInfo.Column("nickname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsXunleiAccount.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysXunleiAccount = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesXunleiAccount = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoXunleiAccount = new TableInfo("xunlei_account", _columnsXunleiAccount, _foreignKeysXunleiAccount, _indicesXunleiAccount);
        final TableInfo _existingXunleiAccount = TableInfo.read(db, "xunlei_account");
        if (!_infoXunleiAccount.equals(_existingXunleiAccount)) {
          return new RoomOpenHelper.ValidationResult(false, "xunlei_account(com.yunx.app.data.db.XunleiAccountEntity).\n"
                  + " Expected:\n" + _infoXunleiAccount + "\n"
                  + " Found:\n" + _existingXunleiAccount);
        }
        final HashMap<String, TableInfo.Column> _columnsBaiduAccount = new HashMap<String, TableInfo.Column>(4);
        _columnsBaiduAccount.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBaiduAccount.put("cookie", new TableInfo.Column("cookie", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBaiduAccount.put("nickname", new TableInfo.Column("nickname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBaiduAccount.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysBaiduAccount = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesBaiduAccount = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoBaiduAccount = new TableInfo("baidu_account", _columnsBaiduAccount, _foreignKeysBaiduAccount, _indicesBaiduAccount);
        final TableInfo _existingBaiduAccount = TableInfo.read(db, "baidu_account");
        if (!_infoBaiduAccount.equals(_existingBaiduAccount)) {
          return new RoomOpenHelper.ValidationResult(false, "baidu_account(com.yunx.app.data.db.BaiduAccountEntity).\n"
                  + " Expected:\n" + _infoBaiduAccount + "\n"
                  + " Found:\n" + _existingBaiduAccount);
        }
        final HashMap<String, TableInfo.Column> _columnsC139Account = new HashMap<String, TableInfo.Column>(5);
        _columnsC139Account.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsC139Account.put("cookie", new TableInfo.Column("cookie", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsC139Account.put("nickname", new TableInfo.Column("nickname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsC139Account.put("authorization", new TableInfo.Column("authorization", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsC139Account.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysC139Account = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesC139Account = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoC139Account = new TableInfo("c139_account", _columnsC139Account, _foreignKeysC139Account, _indicesC139Account);
        final TableInfo _existingC139Account = TableInfo.read(db, "c139_account");
        if (!_infoC139Account.equals(_existingC139Account)) {
          return new RoomOpenHelper.ValidationResult(false, "c139_account(com.yunx.app.data.db.C139AccountEntity).\n"
                  + " Expected:\n" + _infoC139Account + "\n"
                  + " Found:\n" + _existingC139Account);
        }
        final HashMap<String, TableInfo.Column> _columnsPan123Account = new HashMap<String, TableInfo.Column>(5);
        _columnsPan123Account.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPan123Account.put("accessToken", new TableInfo.Column("accessToken", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPan123Account.put("account", new TableInfo.Column("account", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPan123Account.put("nickname", new TableInfo.Column("nickname", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPan123Account.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysPan123Account = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesPan123Account = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoPan123Account = new TableInfo("pan123_account", _columnsPan123Account, _foreignKeysPan123Account, _indicesPan123Account);
        final TableInfo _existingPan123Account = TableInfo.read(db, "pan123_account");
        if (!_infoPan123Account.equals(_existingPan123Account)) {
          return new RoomOpenHelper.ValidationResult(false, "pan123_account(com.yunx.app.data.db.Pan123AccountEntity).\n"
                  + " Expected:\n" + _infoPan123Account + "\n"
                  + " Found:\n" + _existingPan123Account);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "e0b24dccd2684771e52f3ccf8eda66a4", "4eae683fa3a9f9b77f51c2505abdf3ad");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "quark_account","download_task","uc_account","xunlei_account","baidu_account","c139_account","pan123_account");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `quark_account`");
      _db.execSQL("DELETE FROM `download_task`");
      _db.execSQL("DELETE FROM `uc_account`");
      _db.execSQL("DELETE FROM `xunlei_account`");
      _db.execSQL("DELETE FROM `baidu_account`");
      _db.execSQL("DELETE FROM `c139_account`");
      _db.execSQL("DELETE FROM `pan123_account`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(QuarkAccountDao.class, QuarkAccountDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(DownloadTaskDao.class, DownloadTaskDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(UCAccountDao.class, UCAccountDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(XunleiAccountDao.class, XunleiAccountDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(BaiduAccountDao.class, BaiduAccountDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(C139AccountDao.class, C139AccountDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(Pan123AccountDao.class, Pan123AccountDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public QuarkAccountDao quarkAccountDao() {
    if (_quarkAccountDao != null) {
      return _quarkAccountDao;
    } else {
      synchronized(this) {
        if(_quarkAccountDao == null) {
          _quarkAccountDao = new QuarkAccountDao_Impl(this);
        }
        return _quarkAccountDao;
      }
    }
  }

  @Override
  public DownloadTaskDao downloadTaskDao() {
    if (_downloadTaskDao != null) {
      return _downloadTaskDao;
    } else {
      synchronized(this) {
        if(_downloadTaskDao == null) {
          _downloadTaskDao = new DownloadTaskDao_Impl(this);
        }
        return _downloadTaskDao;
      }
    }
  }

  @Override
  public UCAccountDao ucAccountDao() {
    if (_uCAccountDao != null) {
      return _uCAccountDao;
    } else {
      synchronized(this) {
        if(_uCAccountDao == null) {
          _uCAccountDao = new UCAccountDao_Impl(this);
        }
        return _uCAccountDao;
      }
    }
  }

  @Override
  public XunleiAccountDao xunleiAccountDao() {
    if (_xunleiAccountDao != null) {
      return _xunleiAccountDao;
    } else {
      synchronized(this) {
        if(_xunleiAccountDao == null) {
          _xunleiAccountDao = new XunleiAccountDao_Impl(this);
        }
        return _xunleiAccountDao;
      }
    }
  }

  @Override
  public BaiduAccountDao baiduAccountDao() {
    if (_baiduAccountDao != null) {
      return _baiduAccountDao;
    } else {
      synchronized(this) {
        if(_baiduAccountDao == null) {
          _baiduAccountDao = new BaiduAccountDao_Impl(this);
        }
        return _baiduAccountDao;
      }
    }
  }

  @Override
  public C139AccountDao c139AccountDao() {
    if (_c139AccountDao != null) {
      return _c139AccountDao;
    } else {
      synchronized(this) {
        if(_c139AccountDao == null) {
          _c139AccountDao = new C139AccountDao_Impl(this);
        }
        return _c139AccountDao;
      }
    }
  }

  @Override
  public Pan123AccountDao pan123AccountDao() {
    if (_pan123AccountDao != null) {
      return _pan123AccountDao;
    } else {
      synchronized(this) {
        if(_pan123AccountDao == null) {
          _pan123AccountDao = new Pan123AccountDao_Impl(this);
        }
        return _pan123AccountDao;
      }
    }
  }
}
