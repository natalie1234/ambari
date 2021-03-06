/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Ember from 'ember';

export default Ember.Component.extend({
  tagName: 'tabs',

  didInsertElement: function () {
    var tabToActivate,
        tabs = this.get('tabs');

    if (tabs.get('length')) {
      tabToActivate = tabs.find(function (tab) {
        return tab.get('active');
      });

      if (tabToActivate) {
        this.set('selectedTab', tabToActivate);
      } else {
        this.set('selectedTab', tabs.objectAt(0));
      }
    }
  },

  activateTab: function () {
    var selectedTab = this.get('selectedTab');

    selectedTab.set('active', true);

    this.get('tabs').without(selectedTab).forEach(function (tab) {
      tab.set('active', false);
    });
  }.observes('selectedTab'),

  removeEnabled: function () {
    return this.get('canRemove') && this.get('tabs.length') > 1;
  }.property('tabs.@each'),

  actions: {
    remove: function (tab) {
      this.sendAction('removeClicked', tab);
    },

    selectTab: function (tab) {
      this.set('selectedTab', tab);
    },

    titleClick: function(tab) {
      this.sendAction('onActiveTitleClick', tab);
    }
  }
});
