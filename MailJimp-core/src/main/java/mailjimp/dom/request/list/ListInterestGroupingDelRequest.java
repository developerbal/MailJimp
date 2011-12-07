/*
 * Copyright 2011 Michael Laccetti
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
package mailjimp.dom.request.list;

import org.codehaus.jackson.annotate.JsonProperty;

import mailjimp.dom.request.MailJimpRequest;

public class ListInterestGroupingDelRequest extends MailJimpRequest {
  @JsonProperty("grouping_id")
  private Integer groupingId;

  public ListInterestGroupingDelRequest(String apikey, Integer groupingId) {
    super(apikey);
    this.groupingId = groupingId;
  }

  public Integer getGroupingId() {
    return groupingId;
  }

  public void setGroupingId(Integer groupingId) {
    this.groupingId = groupingId;
  }
}