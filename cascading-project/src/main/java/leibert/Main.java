/*
 * Copyright (c) 2007-2008 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Cascading is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cascading is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cascading.  If not, see <http://www.gnu.org/licenses/>.
 */

package leibert;

import java.util.Map;
import java.util.Properties;
import java.util.HashMap;

import cascading.cascade.Cascade;
import cascading.cascade.CascadeConnector;
import cascading.cascade.Cascades;
import cascading.flow.Flow;
import cascading.flow.FlowConnector;
import cascading.operation.Identity;
import cascading.operation.Function;
import cascading.operation.Aggregator;
import cascading.operation.Debug;
import cascading.operation.aggregator.Count;
import cascading.operation.aggregator.Max;
import cascading.operation.regex.*;
import cascading.operation.xml.TagSoupParser;
import cascading.operation.xml.XPathGenerator;
import cascading.operation.xml.XPathOperation;
import cascading.pipe.*;
import cascading.pipe.cogroup.InnerJoin;
import cascading.scheme.SequenceFile;
import cascading.scheme.TextLine;
import cascading.scheme.Scheme;
import cascading.tap.Hfs;
import cascading.tap.Lfs;
import cascading.tap.Tap;
import cascading.tap.SinkMode;
import cascading.tuple.Fields;
/**
 *
 */
public class Main
{


	public static void main(String[] args)
	{
		System.out.println("Starting flow");

		final String click = args[0], user = args[1];
		final String out = args[2];
		Tap table1 = new Hfs(new TextLine(new Fields("line")),click);
		Tap table2 = new Hfs(new TextLine(new Fields("line")),user);
		Tap sink = new Hfs(new TextLine(new Fields("line")),out, SinkMode.REPLACE);
		String regex = "^([^ ]*)\\t+([^ ]*).*";
		Fields common = new Fields("tweet_id");
		Fields declared = new Fields("tweet_id1","click_ip","tweet_id2","uuid");
		Fields fieldsTable1 = new Fields( "tweet_id", "click_ip");
		Fields fieldsTable2 = new Fields( "tweet_id", "uuid");
		int[] allGroups = {1, 2};
		RegexParser parser1 = new RegexParser( fieldsTable1, regex, allGroups );
		RegexParser parser2 = new RegexParser( fieldsTable2, regex, allGroups );
		Pipe assemblyL = new Pipe("lhs");
		Pipe assemblyR = new Pipe("rhs");
		Pipe lhs = new Each( assemblyL, new Fields( "line" ), parser1, Fields.RESULTS );
		Pipe rhs = new Each( assemblyR, new Fields( "line" ), parser2 ,Fields.RESULTS);
		lhs = new Each(lhs,Fields.ALL,new Debug());
		rhs = new Each(rhs,Fields.ALL,new Debug());

		Pipe join = new CoGroup(lhs,common,rhs,common,declared,new InnerJoin());
		join = new GroupBy(join,new Fields("uuid"));
		join = new Every(join,Fields.ALL,new Count(new Fields("count")),Fields.ALL);
		join = new Each(join,Fields.ALL,new Debug());
		Map<String,Tap> sources = new HashMap<String,Tap>();
		sources.put("lhs",table1);
		sources.put("rhs",table2);
		Flow flow =  new FlowConnector().connect("first", sources, sink, join );
		flow.complete();
	}
}

