/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.links.master;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.links.master.types.sfvincomplete.SFVIncomplete;
import org.drftpd.links.master.types.sfvmissing.SFVMissing;
import org.drftpd.links.master.types.zipincomplete.ZipIncomplete;
import org.drftpd.master.commands.*;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.master.vfs.FileHandle;
import org.drftpd.master.vfs.LinkHandle;
import org.drftpd.zipscript.master.sfv.vfs.ZipscriptVFSDataSFV;
import org.drftpd.zipscript.master.zip.vfs.ZipscriptVFSDataZip;

import java.io.FileNotFoundException;
import java.util.LinkedList;

/**
 * @author CyBeR
 * @version $Id: LinkManagerCommands.java 1925 2009-06-15 21:46:05Z CyBeR $
 */

public class LinkManagerCommands extends CommandInterface {
    private static final Logger logger = LogManager.getLogger(LinkManagerCommands.class);

    public void initialize(String method, String pluginName, StandardCommandManager cManager) {
        super.initialize(method, pluginName, cManager);
    }

    /*
     * Used to fix links that are either missing or have been deleted.
     */
    public CommandResponse doSITE_FIXLINKS(CommandRequest request) throws ImproperUsageException {
        if (request.hasArgument()) {
            //throw new ImproperUsageException();
		runFixLinksBest rfl = new runFixLinksBest();
		rfl.dir = request.getCurrentDirectory();
		rfl.start();
        }
        else {

		runFixLinks rfl = new runFixLinks();
		rfl.dir = request.getCurrentDirectory();
		rfl.start();
        }

        CommandResponse response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
        return response;
    }


    private static class runFixLinks extends Thread {
        public DirectoryHandle dir;

        public void run() {
            if (dir != null) {
                LinkManager _linkmanager = LinkManager.getLinkManager();
                LinkedList<DirectoryHandle> dirs = new LinkedList<>();
                dirs.add(dir);
                while (dirs.size() > 0) {
                    DirectoryHandle workingDir = dirs.poll();

                    for (LinkType link : _linkmanager.getLinks()) {

			if (!link.getDirName().contains("ALL-SFV-MISSING-") && !link.getDirName().contains("ALL-SFV-INCOMPLETE-") && !link.getDirName().contains("ALL-ZIP-INCOMPLETE-")) {

				link.doFixLink(workingDir);
			}
                    }

                    try {
                        dirs.addAll(workingDir.getDirectoriesUnchecked());
                    } catch (FileNotFoundException e1) {
                        // ignore - dir no longer exists
                    }
                }
                logger.info("Site Fixlinks - Finished");
            }
        }
    }

    private static class runFixLinksBest extends Thread {

	public DirectoryHandle dir;

		public void run() {

			logger.info("!! LinkManager runFixLinksBest STARTING");

			if (dir != null) {
				LinkManager linkmanager = LinkManager.getLinkManager();

				for (LinkType linkType : linkmanager.getLinks()) {

					if (linkType instanceof SFVMissing) {
						if (linkType.getDirName().contains("ALL-SFV-MISSING")) {

							logger.info("!! LinkManager START BEST1: " + linkType.getDirName());
							DirectoryHandle linkDir = new DirectoryHandle(linkType.getDirName());
							if (linkDir.exists()) {

								try {
									for (LinkHandle link : linkDir.getLinksUnchecked()) {

										handleLinkSFVMissing(link);
									}
								} catch (FileNotFoundException e) {
									logger.warn("!! LinkManager unabe to get links1 - " + linkDir.getName(), e);
									// Ignore
								}
							}
						}
						else {
							logger.info("!! LinkManager START BEST2: " + linkType.getDirName());
							try {
								for (LinkHandle link : dir.getLinksUnchecked()) {

									handleLinkSFVMissing(link);
								}
							} catch (FileNotFoundException e) {
								logger.warn("!! LinkManager unabe to get links11 - " + dir.getName(), e);
								// Ignore
							}
						}
					} else if (linkType instanceof SFVIncomplete) {
						if (linkType.getDirName().contains("ALL-SFV-INCOMPLETE")) {

							logger.info("!! LinkManager START BEST3: " + linkType.getDirName());
							DirectoryHandle linkDir = new DirectoryHandle(linkType.getDirName());
							if (linkDir.exists()) {

								try {
									for (LinkHandle link : linkDir.getLinksUnchecked()) {

										handleLinkSFVIncomplete(link);
									}
								} catch (FileNotFoundException e) {
									logger.warn("!! LinkManager unabe to get links2 - " + linkDir.getName(), e);
									// Ignore
								}
							}
						}
						else {
							logger.info("!! LinkManager START BEST4: " + linkType.getDirName());
							try {
								for (LinkHandle link : dir.getLinksUnchecked()) {

									handleLinkSFVIncomplete(link);
								}
							} catch (FileNotFoundException e) {
								logger.warn("!! LinkManager unabe to get links22 - " + dir.getName(), e);
								// Ignore
							}
						}
					} else if (linkType instanceof ZipIncomplete) {
						if (linkType.getDirName().contains("ALL-ZIP-INCOMPLETE")) {

							logger.info("!! LinkManager START BEST5: " + linkType.getDirName());
							DirectoryHandle linkDir = new DirectoryHandle(linkType.getDirName());
							if (linkDir.exists()) {

								try {
									for (LinkHandle link : linkDir.getLinksUnchecked()) {

										handleLinkZipIncomplete(link);
									}
								} catch (FileNotFoundException e) {
									logger.warn("!! LinkManager unabe to get links4 - " + linkDir.getName(), e);
									// Ignore
								}
							}
						}
						else {
							logger.info("!! LinkManager START BEST4: " + linkType.getDirName());
							try {
								for (LinkHandle link : dir.getLinksUnchecked()) {

									handleLinkZipIncomplete(link);
								}
							} catch (FileNotFoundException e) {
								logger.warn("!! LinkManager unabe to get links44 - " + dir.getName(), e);
								// Ignore
							}
						}
					}
				}
			}

			logger.info("!! LinkManager runFixLinksBest COMPLETED");
		}

		private static void handleLinkSFVMissing(LinkHandle link) {

			if (link.getName().contains("DiRFiX") || link.getName().contains("SAMPLEFiX")
					|| link.getName().contains("PROOFFIX") || link.getName().contains("NFOFIX")
					|| link.getName().contains("NFOFiX") || link.getName().contains("SAMPLEFIX")
					|| link.getName().contains("DIRFIX") || link.getName().contains("DIRFiX")
					|| link.getName().equals("2160P") || link.getName().equals("720P") || link.getName().equals("1080P")
					|| link.getName().equals("2024") || link.getName().equals("2019")
					|| link.getName().equals("Saison.02") || link.getName().equals("TV-2160P-FR")
					|| link.getName().equals("TV-US-720P") || link.getName().equals("CD2")
					|| link.getName().startsWith("[NUKED]-")) {

				try {

					link.deleteUnchecked();
					logger.info("!! LinkManager DELETED LINK1: " + link.getName());
				} catch (Exception ex) {
					logger.warn("!! LinkManager delete failed - " + link.getName(), ex);
				}
			} else {

				DirectoryHandle releaseDirHandle = null;
				try {
					releaseDirHandle = link.getTargetDirectoryUnchecked();
				} catch (Exception ex) {
					logger.warn("!! LinkManager unable to find link target dir - " + link.getName(), ex);
				}

				if (releaseDirHandle != null) {

					try {
						for (FileHandle file : releaseDirHandle.getFilesUnchecked()) {
							if (file.getName().toLowerCase().endsWith(".sfv")) {
								try {

									link.deleteUnchecked();
									logger.info("!! LinkManager DELETED LINK2: " + link.getName());
									return;
								} catch (Exception ex) {
									logger.warn("!! LinkManager delete failed - " + link.getName(), ex);
								}
							}
						}
					} catch (Exception ex) {
						logger.warn("!! LinkManager unable to list files in release - " + releaseDirHandle.getName(),
								ex);
					}
				}
			}
		}

		private static void handleLinkSFVIncomplete(LinkHandle link) {

			if (link.getName().contains("DiRFiX") || link.getName().contains("SAMPLEFiX")
					|| link.getName().contains("PROOFFIX") || link.getName().contains("NFOFIX")
					|| link.getName().contains("NFOFiX") || link.getName().contains("SAMPLEFIX")
					|| link.getName().contains("DIRFIX") || link.getName().contains("DIRFiX")
					|| link.getName().equals("2160P") || link.getName().equals("720P") || link.getName().equals("1080P")
					|| link.getName().equals("2024") || link.getName().equals("2019")
					|| link.getName().equals("Saison.02") || link.getName().equals("TV-2160P-FR")
					|| link.getName().equals("TV-US-720P") || link.getName().equals("CD2")
					|| link.getName().equals("Sample") || link.getName().startsWith("[NUKED]-")) {

				try {

					link.deleteUnchecked();
					logger.info("!! LinkManager DELETED LINK3: " + link.getName());
				} catch (Exception ex) {
					logger.warn("!! LinkManager delete failed - " + link.getName(), ex);
				}
			} else {

				DirectoryHandle releaseDirHandle = null;
				try {
					releaseDirHandle = link.getTargetDirectoryUnchecked();
				} catch (Exception ex) {
					logger.warn("!! LinkManager unable to find link target dir - " + link.getName(), ex);
				}

				ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(releaseDirHandle);

				try {
					if (sfvData.getSFVStatus().isFinished()) {
						link.deleteUnchecked();
						logger.info("!! LinkManager DELETED LINK4: " + link.getName());
					}
				} catch (Exception ex) {
					logger.warn("!! LinkManager delete failed - " + link.getName(), ex);
				}
			}
		}

		private static void handleLinkZipIncomplete(LinkHandle link) {

			if (link.getName().contains("DiRFiX")
					|| link.getName().contains("NFOFIX")
					|| link.getName().contains("NFOFiX")
					|| link.getName().contains("DIRFIX") || link.getName().contains("DIRFiX")
					|| link.getName().startsWith("[NUKED]-")) {

				try {

					link.deleteUnchecked();
					logger.info("!! LinkManager DELETED LINK5: " + link.getName());
				} catch (Exception ex) {
					logger.warn("!! LinkManager delete failed - " + link.getName(), ex);
				}
			} else {

				DirectoryHandle releaseDirHandle = null;
				try {
					releaseDirHandle = link.getTargetDirectoryUnchecked();
				} catch (Exception ex) {
					logger.warn("!! LinkManager unable to find link target dir - " + link.getName(), ex);
				}

				ZipscriptVFSDataZip zipData = new ZipscriptVFSDataZip(releaseDirHandle);
				try {
					if (zipData.getDizStatus().isFinished()) {
						link.deleteUnchecked();
						logger.info("!! LinkManager DELETED LINK5: " + link.getName());
					}
				} catch (Exception ex) {
					logger.warn("!! LinkManager delete failed - " + link.getName(), ex);
				}
			}
		}
	}
}
