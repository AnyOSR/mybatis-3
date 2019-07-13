/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  private final ExpressionEvaluator evaluator;
  private final String collectionExpression;
  private final SqlNode contents;
  private final String open;
  private final String close;
  private final String separator;
  private final String item;       // 配置的item的value
  private final String index;      // 配置的index的value
  private final Configuration configuration;

  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    Map<String, Object> bindings = context.getBindings();          // 获取参数
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);   // 获取实际的iterable (根据表达式获取入参里面的iterable)
    if (!iterable.iterator().hasNext()) {
      return true;
    }
    boolean first = true;
    applyOpen(context);                           // apply open
    int i = 0;

    for (Object o : iterable) {                   // 遍历iterable(入参)
      DynamicContext oldContext = context;
      if (first || separator == null) {
        context = new PrefixedContext(context, "");
      } else {
        context = new PrefixedContext(context, separator);
      }
      int uniqueNumber = context.getUniqueNumber();
      // Issue #709 
      if (o instanceof Map.Entry) {                // 如果入参是map
        @SuppressWarnings("unchecked") 
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        applyIndex(context, mapEntry.getKey(), uniqueNumber);          // 将入参map的key放入context
        applyItem(context, mapEntry.getValue(), uniqueNumber);         // 将入参map的value放入context
      } else {
        applyIndex(context, i, uniqueNumber);    //直接放入index信息
        applyItem(context, o, uniqueNumber);     //放入index对应的value信息
      }
      // 此次循环中，可以直接取到当前index对应的value
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      context = oldContext;
      i++;
    }
    applyClose(context);                     // append close
    context.getBindings().remove(item);      // 移除掉item对应的值
    context.getBindings().remove(index);     // 移除掉index对应的值
    return true;
  }

  //将index相关信息放入context
  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      context.bind(index, o);                    // 将index---o 放入context         //这个有啥用？ 并且这个index是个final
      context.bind(itemizeItem(index, i), o);    // ITEM_PREFIX_index_i---o context
    }
  }

  //将item相关信息放入context
  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      context.bind(item, o);                     // 将item---o 放入context
      context.bind(itemizeItem(item, i), o);     // ITEM_PREFIX_item_i---o context
    }
  }

  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  // ITEM_PREFIX item_i
  private static String itemizeItem(String item, int i) {
    return new StringBuilder(ITEM_PREFIX).append(item).append("_").append(i).toString();
  }

  private static class FilteredDynamicContext extends DynamicContext {
    private final DynamicContext delegate;
    private final int index;
    private final String itemIndex;
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;                 // 解析顺序下表
      this.itemIndex = itemIndex;     // 入参下表索引
      this.item = item;               // 入参索引
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    // 最终的sql是存储在context里面的
    @Override
    public void appendSql(String sql) {
      GenericTokenParser parser = new GenericTokenParser("#{", "}", new TokenHandler() {

        @Override
        public String handleToken(String content) {
          String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));  //空格 item 替换为context里面的参数key
          //如果没有进行替换
          if (itemIndex != null && newContent.equals(content)) {                                                        //空格 item 替换为context里面的参数key
            newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
          }
          return new StringBuilder("#{").append(newContent).append("}").toString();
        }
        // 对文本里面的item文本进行替换，换成context里面的key
      });

      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }


  private class PrefixedContext extends DynamicContext {
    private final DynamicContext delegate;
    private final String prefix;
    private boolean prefixApplied;

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public void appendSql(String sql) {
      if (!prefixApplied && sql != null && sql.trim().length() > 0) {  // 如果还能append 则append prefix
        delegate.appendSql(prefix);
        prefixApplied = true;
      }
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}
