import org.eclipse.jgit.revwalk.RevCommit;

/*
 * Copyright 2014 Brion Swanson bidea.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.gitblit.GitBlit
import com.gitblit.Keys
import com.gitblit.models.RepositoryModel
import com.gitblit.models.TeamModel
import com.gitblit.models.UserModel
import com.gitblit.utils.JGitUtils
import com.gitblit.utils.JsonUtils
import com.sun.org.apache.xalan.internal.xsltc.compiler.Import;

import java.text.SimpleDateFormat
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceiveCommand.Result
import org.slf4j.Logger

import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.util.Set;
import java.util.HashSet;

/**
 * Sample Gitblit Post-Receive Hook: mantisbt
 *
 * The purpose of this script is to invoke the Mantis Bugtracker source integration hook and 
 * update a specified case (in the commit message) when a push is received.
 * 
 * Example URL - http://bugs.yourdomain.com/mantis/plugin.php?page=Source/checkin&api_key=12345
 * 
 * The Post-Receive hook is executed AFTER the pushed commits have been applied
 * to the Git repository.  This is the appropriate point to trigger an
 * integration build or to send a notification.
 * 
 * This script is only executed when pushing to *Gitblit*, not to other Git
 * tooling you may be using.
 * 
 * If this script is specified in *groovy.postReceiveScripts* of gitblit.properties
 * or web.xml then it will be executed by any repository when it receives a
 * push.  If you choose to share your script then you may have to consider
 * tailoring control-flow based on repository access restrictions.
 *
 * Scripts may also be specified per-repository in the repository settings page.
 * Shared scripts will be excluded from this list of available scripts.
 * 
 * This script is dynamically reloaded and it is executed within it's own
 * exception handler so it will not crash another script nor crash Gitblit.
 *
 * If you want this hook script to fail and abort all subsequent scripts in the
 * chain, "return false" at the appropriate failure points.
 * 
 * Bound Variables:
 *  gitblit			Gitblit Server	 			com.gitblit.GitBlit
 *  repository		Gitblit Repository			com.gitblit.models.RepositoryModel
 *  receivePack		JGit Receive Pack			org.eclipse.jgit.transport.ReceivePack
 *  user			Gitblit User				com.gitblit.models.UserModel
 *  commands		JGit commands 				Collection<org.eclipse.jgit.transport.ReceiveCommand>
 *	url				Base url for Gitblit		String
 *  logger			Logs messages to Gitblit 	org.slf4j.Logger
 *  clientLogger	Logs messages to Git client	com.gitblit.utils.ClientLogger
 *
 * Accessing Gitblit Custom Fields:
 *   def myCustomField = repository.customFields.myCustomField
 * 
 * Cusom Fileds Used by This script
 *   mantisBTUrl - base URL to MantisBT (ie. http://bugs.yourdomain.com/mantis/)
 *   mantisBTApiKey - API key configured in MantisBT at ${mantisBTUrl}/plugin.php?page=Source/manage_config_page
 *   mantisBTBugIdRegex - regex pattern used to match on bug id in a commit message
 */

// Indicate we have started the script
logger.info("mantisbt hook triggered by ${user.username} for ${repository.name}")

/*
 * Primitive email notification.
 * This requires the mail settings to be properly configured in Gitblit.
 */

Repository r = gitblit.getRepository(repository.name)

// pull custom fields from repository specific values
// groovy.customFields = "mantisBTUrl=MantisBT Base URL" "mantisBTApiKey=MantisBT API Key"
def urlBase = repository.customFields.mantisBTUrl
def apiKey = repository.customFields.mantisBTApiKey

logger.info("urlBase: ${urlBase}\napiKey: ${apiKey}\ncommands: ${commands}")

for (command in commands) {
	logger.info("handling command ${command}")
	
	for( commit in JGitUtils.getRevLog(r, command.oldId.name, command.newId.name).reverse() ) {
		logger.info("processing commit ${commit.id}")

		Set<String> adds = new HashSet<>();
		Set<String> mods = new HashSet<>();
		Set<String> dels = new HashSet<>();
		
		for( diff in getDiffs(r, commit) ) {
			if (diff.changeType == DiffEntry.ChangeType.ADD ||
				diff.changeType == DiffEntry.ChangeType.COPY) {
				adds.add(diff.newPath);
			} else if (diff.changeType == DiffEntry.ChangeType.DELETE) {
				dels.add(diff.newPath);
			} else if (diff.changeType == DiffEntry.ChangeType.RENAME ||
					   diff.changeType == DiffEntry.ChangeType.MODIFY) {
			    mods.add(diff.newPath);
			}
		}
		
		// Example URL - http://bugs.yourdomain.com/mantis/plugin.php?page=Source/checkin&apiKey=${apiKey}
		
		// uncomment the following line for the real urlString
		//def urlString = "${urlBase}/plugin.php?page=Source/checkin&api_key=${apiKey}"
		
		// this urlString uses RequestBin for testing (to see what gitblit is sending to mantis
		def urlString = "http://requestb.in/rh128zrh"
		logger.info( urlString );
		
		// Post the payload data as JSON to the URL and make sure we get an "OK" response
		// The payload structure supports multiple commits, but for pushing commits into Mantis
		// we push one at a time since Mantis doesn't know how to handle a multi-commit push
		
		// the 'source' entry is to help Mantis identify which Source plugin should handle this payload
		def payloadMap = [
			payload:[ 
				source:"gitblit", 
				commits:[
					commit: [
						author:[
							email:commit.authorIdent.emailAddress,
							name:commit.authorIdent.name
						],
					    committer:[
							email:commit.committerIdent.emailAddress,
							name:commit.committerIdent.name
						],
						added:adds,
						modified:mods,
						removed:dels,
						id:ObjectId.toString(commit.id),
						branch:r.branch,
						url:url+"/commit/"+repository.name+"/"+ObjectId.toString(commit.id),
						message:commit.fullMessage
					]						    
				]
			] 
		] 

		def jsonPayload = JsonUtils.toJsonString(payloadMap)

		logger.info("sending payload (${jsonPayload}) to ${urlString}")
		
		def mantisUrl = new URL(urlString)
		def connection = mantisUrl.openConnection()
		connection.setRequestMethod("POST")
		connection.doOutput = true

		def writer = new OutputStreamWriter(connection.outputStream)
		writer.write(jsonPayload)
		writer.flush()
		writer.close()
		connection.connect()

		def responseString = connection.content.text
           
		if( !"OK".equals(responseString) ) {
			throw new Exception( "Problem posting ${mantisUrl} - ${responseString}" );
		}
	}
}
// close the repository reference
r.close()

/**
 * For a given commit, find all files changed as part of it.
 */
def Set<DiffEntry> getDiffs(Repository r, RevCommit commit) {
	DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)
	formatter.setRepository(r)
	formatter.setDetectRenames(true)
	formatter.setDiffComparator(RawTextComparator.DEFAULT);

	def diffs
	RevWalk rw = new RevWalk(r)
	if (commit.parentCount > 0) {
		RevCommit parent = rw.parseCommit(commit.parents[0].id)
		diffs = formatter.scan(parent.tree, commit.tree)
	} else {
		diffs = formatter.scan(new EmptyTreeIterator(),
							   new CanonicalTreeParser(null, rw.objectReader, commit.tree))
	}
	rw.dispose()
	
	return diffs;
}
