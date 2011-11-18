/*
 * Copyright 2010-2011 Michael Laccetti
 * 
 * This file is part of MailJimp.
 * 
 * MailJimp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 * 
 * MailJimp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with MailJimp.  If not, see <http://www.gnu.org/licenses/>.
 */
package mailjimp.service.impl;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mailjimp.dom.*;
import mailjimp.dom.list.MailingList;
import mailjimp.dom.list.MemberInfo;
import mailjimp.dom.security.ApiKey;
import mailjimp.service.MailJimpException;
import mailjimp.service.UnexpectedMailJimpResponseException;
import mailjimp.util.ParserHint;
import mailjimp.util.ParserUtils;

@SuppressWarnings("serial")
public class MailJimpParser implements Serializable {
  private static final Pattern SETTER_PATTERN = Pattern.compile("set(\\w+)");

  /**
   * This is not part of the public API - it is public just for testing purposes.  Use at your own risk.
   * @param <T>
   * @param results
   * @param obj
   * @throws Exception
   */
  public <T> void setVars(Map<String, Object> results, T obj) throws Exception {
    // check for any hints
    Map<String, ParserHint> hints = null;
    if (IHasParserHints.class.isAssignableFrom(obj.getClass())) {
      hints = ((IHasParserHints) obj).getHints();
    }
    for (Method m : obj.getClass().getMethods()) {
      Matcher matcher = SETTER_PATTERN.matcher(m.getName());
      if (matcher.matches() && m.getParameterTypes().length == 1) {
        String key = ParserUtils.convertKey(matcher.group(1));
        Object value = findValue(results, key, hints);
        // convert and set the value
        if (value == null) {
          m.invoke(obj, value);
        } else {
          try {
            Class<?> param = m.getParameterTypes()[0];
            if (param.isAssignableFrom(value.getClass()) || Boolean.class.isAssignableFrom(value.getClass())) {
              m.invoke(obj, value);
            } else if (value.getClass().isArray() && param.isArray()) {
              Object[] array = (Object[]) value;
              Object[] typedArray = (Object[]) Array.newInstance(param.getComponentType(), array.length);
              for (int i = 0; i < array.length; i++) {
                Object o = array[i];
                typedArray[i] = convert(param.getComponentType(), o);
              }
              // we have to wrap the array in another array to prevent an
              // IllegalArgumentException
              m.invoke(obj, new Object[] { typedArray });
            } else {
              m.invoke(obj, convert(param, value));
            }
          } catch (IllegalArgumentException e) {
            // noinspection MalformedFormatString
            throw new IllegalArgumentException(String.format("Error setting %1$s. %n%2$s", key, e.getMessage(), e));
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Object findValue(Map<String, Object> results, String key, Map<String, ParserHint> hints) {
    Object value = results.get(key);
    if (null == value && null != hints && hints.containsKey(key)) {
      ParserHint hint = hints.get(key);
      String[] steps = hint.getSteps();
      String step;
      Map<String, Object> map = results;
      for (int i = 0, j = steps.length; i < steps.length; i++) {
        step = steps[i];
        if (i + 1 == j) {
          value = map.get(step);
        } else {
          // noinspection unchecked
          map = (Map<String, Object>) map.get(step);
        }
      }
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private <T> T convert(Class<T> expected, Object value) throws Exception {
    if (value == null) { return null; }
    if (expected.isEnum()) {
      try {
        for (T obj : expected.getEnumConstants()) {
          if (obj.toString().equalsIgnoreCase((String) value)) { return obj; }
        }
        throw new MailJimpException(String.format("No enum constant found for value %s in enum %s.", value.toString(), expected.getSimpleName()));
      } catch (Exception ex) {
        throw new MailJimpException("Could not parse Enum.", ex);
      }
    }
    if (expected.equals(Date.class) && value.getClass().equals(String.class)) {
      try {
        String s = (String) value;
        if (s.length() == 0) { return null; }
        return (T) MailJimpConstants.SDF.parse((String) value);
      } catch (ParseException pe) {
        throw new MailJimpException("Could not convert date.", pe);
      }
    }
    if (expected.equals(Double.class)) {
      if (value.getClass().equals(Integer.class)) {
        return (T) Double.valueOf(((Integer) value).toString());
      } else if (value.getClass().equals(String.class)) { return (T) Double.valueOf((String) value); }
    }
    if (expected.equals(Integer.class)) {
      if (value.getClass().equals(Double.class)) {
        return (T) Integer.valueOf(((Double) value).intValue());
      } else if (value.getClass().equals(String.class)) { return (T) Integer.valueOf(((String) value)); }
    }
    if (List.class.isAssignableFrom(expected)) {
      if (value.getClass().isArray()) {
        return null;
      } else if (value.getClass().isAssignableFrom(Map.class)) {
        try {
          T obj = expected.newInstance();
          setVars((Map<String, Object>) value, obj);
        } catch (Exception ex) {
          throw new MailJimpException("Could not process nested collection.", ex);
        }
      }
      throw new MailJimpException("We expected a list, but the inbound element was not an array.");
    }
    // TODO: how to check for complex types in a more general way?
    if (IParsableProperty.class.isAssignableFrom(expected) && Map.class.isAssignableFrom(value.getClass())) {
      T inner = expected.newInstance();
      setVars((Map<String, Object>) value, inner);
      return inner;
    }
    throw new IllegalArgumentException(String.format("Could not convert from %s to %s.", value.getClass().getSimpleName(), expected.getSimpleName()));
  }

  public List<ApiKey> parseApiKeys(Object results) throws MailJimpException {
    List<ApiKey> keys = new ArrayList<ApiKey>();
    if (results instanceof Object[]) {
      for (Object o : (Object[]) results) {
        if (o instanceof Map<?, ?>) {
          @SuppressWarnings("unchecked")
          Map<String, Object> m = (Map<String, Object>) o;
          ApiKey ak = new ApiKey();
          try {
            setVars(m, ak);
          } catch (Exception ex) {
            throw new MailJimpException("Could not set fields.", ex);
          }
          keys.add(ak);
        }
      }
    }
    return keys;
  }

  public String createApiKey(Object results) throws MailJimpException {
    if (results instanceof String) { return (String) results; }
    throw new MailJimpException(String.format("Result was an unxpected type: %s.", results.getClass().getName()));
  }

  public Boolean expireApiKey(Object results) throws MailJimpException {
    if (results instanceof Boolean) { return (Boolean) results; }
    throw new MailJimpException(String.format("Result was an unxpected type: %s.", results.getClass().getName()));
  }

  /**
   * This will parse all list information of the current account.
   * 
   * @param results
   *          This is the Object created out of the xml-rpc-call to the
   *          MailChimp API.
   * 
   * @return A list containing {@link MailingList MailingLists}.
   * 
   * @throws MailJimpException
   *           If parsing goes wrong
   */
  public List<MailingList> parseLists(Object results) throws MailJimpException {
    List<MailingList> lists;
    if (results instanceof Object[]) { // api version 1.2
      lists = new ArrayList<MailingList>();
      _parseLists((Object[]) results, lists);
    } else if (Map.class.isAssignableFrom(results.getClass())) { // api version
                                                                 // 1.3
      @SuppressWarnings("unchecked")
      Map<String, Object> r = (Map<String, Object>) results;
      lists = new ArrayList<MailingList>((Integer) r.get("total"));
      _parseLists((Object[]) r.get("data"), lists);
    } else {
      throw new UnexpectedMailJimpResponseException("Unsupported api version?");
    }
    return lists;
  }

  private void _parseLists(Object[] results, List<MailingList> lists) throws MailJimpException {
    for (Object o : results) {
      if (o instanceof Map<?, ?>) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        MailingList ml = new MailingList();
        try {
          setVars(m, ml);
          lists.add(ml);
        } catch (Exception ex) {
          throw new MailJimpException("Could not set fields.", ex);
        }
      }
    }
  }

  /**
   * This will parse all members of a list.
   * 
   * @param results
   *          This is the Object created out of the xml-rpc-call to the
   *          MailChimp API.
   * 
   * @return A map containing e-mail-addresses as keys and subscription dates as
   *         values.
   * 
   * @throws MailJimpException
   *           If parsing goes wrong.
   */
  public Map<String, Date> parseListMembers(Object results) throws MailJimpException {
    Map<String, Date> members;
    if (results instanceof Object[]) {
      // api version 1.2
      members = new HashMap<String, Date>();
      _parseListMembers((Object[]) results, members);
    } else if (Map.class.isAssignableFrom(results.getClass())) {
      // api version 1.3
      @SuppressWarnings("unchecked")
      Map<String, Object> r = (Map<String, Object>) results;
      members = new HashMap<String, Date>((Integer) r.get("total"));
      _parseListMembers((Object[]) r.get("data"), members);
    } else {
      throw new UnexpectedMailJimpResponseException("Unsupported api version?");
    }
    return members;
  }

  private void _parseListMembers(Object[] results, Map<String, Date> members) throws MailJimpException {
    for (Object o : results) {
      if (o instanceof Map<?, ?>) {
        @SuppressWarnings("unchecked")
        Map<String, String> m = (Map<String, String>) o;
        String email = m.get("email");
        String timestamp = m.get("timestamp");
        try {
          members.put(email, MailJimpConstants.SDF.parse(timestamp));
        } catch (ParseException pe) {
          throw new MailJimpException("Could not parse member list timestamp.", pe);
        }
      }
    }
  }

  /**
   * This will parse all member information retrieved from the call to
   * <code>listMemberInfo</code> of the MailChimp API. We are silently ignoring
   * any unsuccessful lookups (unknown email addresses or ids) as this method is
   * returning only one MemberInfo at a time. This is because that was the way
   * they did it back in the 1.2 days.
   * 
   * @param results
   *          This is the Object created out of the xml-rpc-call to the
   *          MailChimp API.
   * 
   * @return A {@link MemberInfo} containing - you guess it - the members info.
   * 
   * @throws MailJimpException
   *           If parsing goes wrong.
   */
  @SuppressWarnings("unchecked")
  public MemberInfo parseListMemberInfo(Object results) throws MailJimpException {
    if (results instanceof Map<?, ?>) {
      MemberInfo mi = new MemberInfo();
      Map<String, Object> m = (Map<String, Object>) results;
      if (m.containsKey("data")) { // this is version 1.3
        m = (Map<String, Object>) ((Object[]) m.get("data"))[0];
      }
      try {
        setVars(m, mi);
        return mi;
      } catch (Exception ex) {
        throw new MailJimpException("Could not set fields.", ex);
      }
    }
    throw new MailJimpException(formatErrorMsg(results, "Result from MailChimp API was not of the expected type (instead, it was %s)."));
  }

  public boolean parseListSubscribe(Object results) throws MailJimpException {
    if (results instanceof Boolean) { return (Boolean) results; }
    throw new MailJimpException(formatErrorMsg(results, "List subscription result type was not boolean (was: %s)."));
  }

  public BatchResult parseListBatchSubscribe(Object results) throws MailJimpException {
    return _parseBatchResult(results);
  }

  /**
   * This will parse all responses of any batch jobs.
   * 
   * @param results
   *          Guess what!
   * 
   * @return The parsed result.
   * @throws mailjimp.service.MailJimpException
   *           if something goes wrong.
   */
  private BatchResult _parseBatchResult(Object results) throws MailJimpException {
    if (results instanceof Map<?, ?>) {
      BatchResult br = new BatchResult();
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) results;
      try {
        setVars(m, br);
        return br;
      } catch (Exception ex) {
        throw new MailJimpException("Could not set fields.", ex);
      }
    }
    throw new MailJimpException(formatErrorMsg(results, "List batch subscription result was not of the expected type (instead, it was %s)."));
  }

  public boolean parseListUpdateMember(Object results) throws MailJimpException {
    if (results instanceof Boolean) { return (Boolean) results; }
    throw new MailJimpException(formatErrorMsg(results, "List update member result type was not boolean (was: %s)."));
  }

  public boolean parseListUnsubscribe(Object results) throws MailJimpException {
    if (results instanceof Boolean) { return (Boolean) results; }
    throw new MailJimpException(formatErrorMsg(results, "List unsubscription result type was not boolean (was: %s)."));
  }

  private String formatErrorMsg(Object results, final String msg) {
    return String.format(msg, (null != results ? results.getClass().getSimpleName() : results));
  }
}