/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.scan;

import org.apache.commons.lang.StringUtils;
import org.sonar.api.BatchComponent;
import org.sonar.api.resources.Resource;
import org.sonar.api.resources.ResourceUtils;
import org.sonar.api.utils.HttpDownloader;
import org.sonar.batch.bootstrap.AnalysisMode;
import org.sonar.batch.bootstrap.ServerClient;
import org.sonar.core.source.db.SnapshotSourceDao;

import javax.annotation.CheckForNull;

public class LastSnapshots implements BatchComponent {

  private final AnalysisMode analysisMode;
  private final ServerClient server;
  private final SnapshotSourceDao sourceDao;

  public LastSnapshots(AnalysisMode analysisMode, SnapshotSourceDao dao, ServerClient server) {
    this.analysisMode = analysisMode;
    this.sourceDao = dao;
    this.server = server;
  }

  @CheckForNull
  public String getSource(Resource resource) {
    String source = null;
    if (ResourceUtils.isFile(resource)) {
      if (analysisMode.isPreview()) {
        source = loadSourceFromWs(resource);
      } else {
        source = loadSourceFromDb(resource);
      }
    }
    return StringUtils.defaultString(source, "");
  }

  @CheckForNull
  private String loadSourceFromWs(Resource resource) {
    try {
      return server.request("/api/sources?resource=" + resource.getEffectiveKey() + "&format=txt", false, analysisMode.getPreviewReadTimeoutSec() * 1000);
    } catch (HttpDownloader.HttpException he) {
      if (he.getResponseCode() == 404) {
        return "";
      }
      throw he;
    }
  }

  @CheckForNull
  private String loadSourceFromDb(Resource resource) {
    return sourceDao.selectSnapshotSourceByComponentKey(resource.getEffectiveKey());
  }
}
