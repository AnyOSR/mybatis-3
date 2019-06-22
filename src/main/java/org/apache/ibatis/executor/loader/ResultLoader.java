/**
 *    Copyright 2009-2015 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor.loader;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * @author Clinton Begin
 */
public class ResultLoader {

  protected final Configuration configuration;
  protected final Executor executor;
  protected final MappedStatement mappedStatement;
  protected final Object parameterObject;
  protected final Class<?> targetType;
  protected final ObjectFactory objectFactory;
  protected final CacheKey cacheKey;
  protected final BoundSql boundSql;
  protected final ResultExtractor resultExtractor;
  protected final long creatorThreadId;   // 创建这个对象的线程Id
  
  protected boolean loaded;
  protected Object resultObject;

  // 一个mappedStatement只会有一个ResultLoader？
  public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement, Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
    this.configuration = config;
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.parameterObject = parameterObject;
    this.targetType = targetType;
    this.objectFactory = configuration.getObjectFactory();
    this.cacheKey = cacheKey;
    this.boundSql = boundSql;
    this.resultExtractor = new ResultExtractor(configuration, objectFactory);
    this.creatorThreadId = Thread.currentThread().getId();
  }

  public Object loadResult() throws SQLException {
    List<Object> list = selectList();
    resultObject = resultExtractor.extractObjectFromList(list, targetType);
    return resultObject;
  }

  private <E> List<E> selectList() throws SQLException {
    Executor localExecutor = executor;
    // 如果当前线程不是创建这个对象的线程 或者执行器已经关闭
    // 则新建一个执行器
    if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
      localExecutor = newExecutor();
    }
    try {
      return localExecutor.<E> query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
    } finally {
      if (localExecutor != executor) {  // 如果线程变更过，则关闭新生成的localExecutor
        localExecutor.close(false);
      }
    }
  }

  // 创建一个Executor
  private Executor newExecutor() {
    final Environment environment = configuration.getEnvironment();
    if (environment == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
    }
    final DataSource ds = environment.getDataSource();                                       // 从environment中获取数据源
    if (ds == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
    }
    final TransactionFactory transactionFactory = environment.getTransactionFactory();          // 从environment中获取事务工厂
    final Transaction tx = transactionFactory.newTransaction(ds, null, false); // 创建事务
    return configuration.newExecutor(tx, ExecutorType.SIMPLE);                                  // 创建一个Executor，包含一个事务
  }

  public boolean wasNull() {
    return resultObject == null;
  }

}
